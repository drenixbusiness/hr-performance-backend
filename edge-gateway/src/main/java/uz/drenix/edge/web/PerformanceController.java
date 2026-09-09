package uz.drenix.edge.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.drenix.edge.web.dto.PerformanceViews;
import uz.drenix.identity.grpc.v1.Entity;
import uz.drenix.identity.grpc.v1.HrPerformanceRequest;
import uz.drenix.identity.grpc.v1.PerformanceServiceGrpc;
import uz.drenix.platform.security.AccessDeniedException;
import uz.drenix.platform.security.AuthPrincipal;

/**
 * The recruiting performance chart.
 *
 * <p>Everything here is derived from the monday.com board on each call — this system stores no
 * driver records of its own, and nothing on this path writes back to monday.
 */
@RestController
@RequestMapping("/api/v1/performance")
@Tag(name = "Performance")
public class PerformanceController {

    private final PerformanceServiceGrpc.PerformanceServiceBlockingStub performanceService;

    public PerformanceController(PerformanceServiceGrpc.PerformanceServiceBlockingStub service) {
        this.performanceService = service;
    }

    @Operation(
            summary = "Recruiting chart for a calendar year",
            description = """
                    One series per recruiter, twelve months each: how many drivers they hired, how \
                    many left, and how many were on the books at the end of the month. Needs \
                    `performance:read`, which `SUPER_ADMIN`, `CEO` and `HR_LEAD` hold.

                    ## The three lines

                    **hired** — drivers whose hire date falls in that month. A hire belongs to the \
                    month it happened and stays there: somebody recruited in May who leaves in \
                    August is still a May hire, because that is when the work was done.

                    **terminated** — drivers whose leaving date falls in that month.

                    **active** — how many were on the books when the month ended: hired by then and \
                    not yet gone. A point-in-time count, so it is *not* the running total of hired \
                    minus terminated.

                    ## Quarters and targets

                    Quarters are counted from each recruiter's own start — their earliest hire on \
                    the board — not from January. Two people who joined in different months are \
                    therefore measured at the same point in their own tenure.

                    | Quarter | Hires/month | Hires in quarter | Active at end |
                    |---|---|---|---|
                    | 1 | 4 | 12 | 7 |
                    | 2 | 6 | 18 | 11 |
                    | 3 | 8 | 24 | 14 |
                    | 4 | 10 | 30 | 18 |

                    After a full year: **50 active drivers**.

                    A quarter still running reports its numbers so far with `complete: false`, and \
                    `hiresMet` / `activeMet` should not be read as failure until it ends.

                    Each month carries the `quarter` it belongs to for that recruiter, or 0 when it \
                    falls outside their measured first year. Months that have not happened yet come \
                    back with `future: true` and zeroes — draw your lines up to the last one that \
                    is false.

                    ## Read `dataIssues` before trusting a dip

                    Rows the board cannot fully account for are listed rather than hidden: drivers \
                    with no hire date, terminations with no date, leaving dates that fall before \
                    the hire date. A month that looks bad may simply be a month somebody forgot to \
                    fill in.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The chart, one entry per recruiter."),
            @ApiResponse(responseCode = "400", description = "`year` is outside 2000–2100.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `performance:read`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503",
                    description = "The monday.com board could not be read and no earlier snapshot "
                                  + "was cached.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping
    @PreAuthorize("hasAuthority('performance:read')")
    public PerformanceViews.ChartView chart(
            @Parameter(description = "Calendar year to chart. Defaults to the current one.",
                       example = "2026")
            @RequestParam(required = false) @Min(2000) @Max(2100) Integer year,

            @Parameter(description = "One recruiter only, as spelled in the board's Source column. "
                                     + "Matched without regard to case. Leave empty for everyone.",
                       example = "Alex")
            @RequestParam(required = false) @Size(max = 64) String recruiter,

            @Parameter(description = "`JM` or `BP`. Each company keeps its own monday board, and a "
                                     + "recruiter is on exactly one of them. Omit to read every "
                                     + "board you are allowed to see.",
                       example = "BP")
            @RequestParam(required = false) String entity,

            @AuthenticationPrincipal AuthPrincipal principal) {

        HrPerformanceRequest.Builder request = HrPerformanceRequest.newBuilder()
                .setYear(year == null ? 0 : year)
                .setRecruiter(recruiter == null ? "" : recruiter)
                .setEntity(entity == null || entity.isBlank()
                        ? visibleTo(principal)
                        : requireVisible(principal, entity));

        return PerformanceViews.of(performanceService.getHrPerformance(request.build()));
    }

    /**
     * The board a caller sees when they name none.
     *
     * <p>Not "every board": the two companies keep separate hiring, and a JM lead has no business
     * reading BP's. Somebody scoped to one company is given that one; somebody whose grants cover
     * both — a CEO, an administrator — is given {@code UNSPECIFIED}, which performance-service
     * reads as every configured board.
     *
     * <p>This is the whole of the chart's visibility rule. `performance:read` says a chart may be
     * read at all; the entity scope on the grant says whose.
     */
    private static Entity visibleTo(AuthPrincipal principal) {
        boolean jm = principal.canActOn(AuthPrincipal.EntityScope.JM);
        boolean bp = principal.canActOn(AuthPrincipal.EntityScope.BP);

        if (jm && bp) {
            return Entity.ENTITY_UNSPECIFIED;
        }
        if (jm) {
            return Entity.ENTITY_JM;
        }
        if (bp) {
            return Entity.ENTITY_BP;
        }
        // A grant scoped to a company this build does not know about. Refusing beats guessing.
        throw new AccessDeniedException("performance:read for any company");
    }

    /**
     * The company the caller asked for, if they are allowed to see it.
     *
     * <p>Checked here rather than downstream for the same reason every other visibility rule is:
     * performance-service is handed a board to read and does not know who anybody is. Without
     * this, a JM lead could ask for {@code ?entity=BP} and read another company's hiring.
     */
    private static Entity requireVisible(AuthPrincipal principal, String entity) {
        AuthPrincipal.EntityScope scope;
        Entity requested;
        switch (entity.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "JM" -> {
                requested = Entity.ENTITY_JM;
                scope = AuthPrincipal.EntityScope.JM;
            }
            case "BP" -> {
                requested = Entity.ENTITY_BP;
                scope = AuthPrincipal.EntityScope.BP;
            }
            default -> throw new uz.drenix.edge.web.BadRequestException("entity must be JM or BP");
        }
        if (!principal.canActOn(scope)) {
            throw new AccessDeniedException("performance:read for " + entity);
        }
        return requested;
    }
}
