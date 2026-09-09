package uz.drenix.edge.web;

import com.google.protobuf.Timestamp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.drenix.edge.web.dto.Views;
import uz.drenix.identity.grpc.v1.ListAuditRequest;
import uz.drenix.identity.grpc.v1.PageRequest;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;

/**
 * Read access to the audit trail.
 *
 * <p>Read-only by construction. The table refuses UPDATE and DELETE at the database level and no
 * RPC exists to amend an entry, so there is nothing here but a query — a trail that the people it
 * records can edit is not a trail.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
@Tag(name = "Admin · Audit")
public class AdminAuditController {

    private final UserServiceGrpc.UserServiceBlockingStub userService;

    public AdminAuditController(UserServiceGrpc.UserServiceBlockingStub userService) {
        this.userService = userService;
    }

    @Operation(
            summary = "Search the audit trail",
            description = """
                    Every recorded action, newest first. Needs `audit:read`, which `SUPER_ADMIN`, \
                    `AUDITOR`, `HR_LEAD` and `CEO` hold.

                    Entries are written for both successes and failures — a rejected login and a \
                    denied permission are exactly the rows worth keeping — so filter on `outcome` \
                    when you are looking for trouble rather than for history.

                    Every filter is optional and they combine with AND. With none of them set you \
                    get the most recent page of everything.

                    Paging is by cursor: take `nextCursor` from the response and send it back as \
                    `cursor`. The cursor is the id of the last entry you saw, and because entries \
                    are never removed a page boundary cannot drift while you read.

                    `correlationId` on each entry matches the `X-Correlation-Id` header the API \
                    returned for that request, so a user's bug report can be tied to what the \
                    system actually recorded.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of audit entries."),
            @ApiResponse(responseCode = "400",
                    description = "`limit` is outside 1–200, `outcome` is not one of the three "
                                  + "values, or a timestamp is not ISO-8601.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `audit:read`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping
    @PreAuthorize("hasAuthority('audit:read')")
    public Views.PageView<Views.AuditEntryView> list(
            @Parameter(description = "Only actions taken by this account. Exact username, not a "
                                     + "search.",
                       example = "admin")
            @RequestParam(required = false) @Size(max = 64) String actor,

            @Parameter(description = "Only this action. Exact match — see the `action` field of any "
                                     + "entry for the vocabulary in use.",
                       example = "user.create")
            @RequestParam(required = false) @Size(max = 64) String action,

            @Parameter(description = "SUCCESS for things that worked, DENIED for permission "
                                     + "refusals, FAILURE for attempts that were rejected such as "
                                     + "a wrong password.",
                       example = "FAILURE")
            @RequestParam(required = false)
            @Pattern(regexp = "SUCCESS|DENIED|FAILURE",
                     message = "must be SUCCESS, DENIED or FAILURE") String outcome,

            @Parameter(description = "Everything that happened to one thing — usually a user id. "
                                     + "The full history of an account in one call.",
                       example = "fe453cec-7e17-4778-9eff-82b1b9243408")
            @RequestParam(required = false) @Size(max = 64) String targetId,

            @Parameter(description = "Inclusive lower bound, ISO-8601 with a zone.",
                       example = "2026-08-25T00:00:00Z")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,

            @Parameter(description = "Exclusive upper bound, ISO-8601 with a zone.",
                       example = "2026-08-26T00:00:00Z")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,

            @Parameter(description = "The `nextCursor` from the previous page. Leave empty to start "
                                     + "at the newest entry.")
            @RequestParam(required = false) @Size(max = 64) String cursor,

            @Parameter(description = "How many entries to return, 1–200.", example = "50")
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {

        ListAuditRequest.Builder request = ListAuditRequest.newBuilder()
                .setPage(PageRequest.newBuilder()
                        .setCursor(nullToEmpty(cursor))
                        .setLimit(limit)
                        .build())
                .setActorUsername(nullToEmpty(actor))
                .setAction(nullToEmpty(action))
                .setOutcome(nullToEmpty(outcome))
                .setTargetId(nullToEmpty(targetId));

        if (from != null) {
            request.setOccurredFrom(toProto(from));
        }
        if (to != null) {
            request.setOccurredTo(toProto(to));
        }
        return Views.of(userService.listAudit(request.build()));
    }

    private static Timestamp toProto(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
