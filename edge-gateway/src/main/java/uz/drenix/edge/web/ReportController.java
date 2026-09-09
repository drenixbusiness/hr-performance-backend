package uz.drenix.edge.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.drenix.edge.report.MonthlyReportPdf;
import uz.drenix.edge.web.dto.ReportViews;
import uz.drenix.identity.grpc.v1.ActivityStandard;
import uz.drenix.identity.grpc.v1.ActivityStandardSettings;
import uz.drenix.identity.grpc.v1.Empty;
import uz.drenix.identity.grpc.v1.Entity;
import uz.drenix.identity.grpc.v1.FeedbackRequest;
import uz.drenix.identity.grpc.v1.FeedbackResponse;
import uz.drenix.identity.grpc.v1.PerformanceServiceGrpc;
import uz.drenix.identity.grpc.v1.ReportEventServiceGrpc;
import uz.drenix.identity.grpc.v1.ReportGeneratedRequest;
import uz.drenix.identity.grpc.v1.ReportSubject;
import uz.drenix.identity.grpc.v1.SettingsServiceGrpc;
import uz.drenix.identity.grpc.v1.TeamReportRequest;
import uz.drenix.identity.grpc.v1.TeamReportResponse;
import uz.drenix.identity.grpc.v1.User;
import uz.drenix.platform.security.AuthPrincipal;

/**
 * The monthly recruiting report.
 *
 * <p>Who appears is decided here, by exactly the rule the activity endpoint uses — a lead sees
 * their own company and nobody else's. performance-service is handed a list of people and reports
 * on those; it has no idea who anybody is, and so cannot widen the answer.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final PerformanceServiceGrpc.PerformanceServiceBlockingStub performanceService;
    private final SettingsServiceGrpc.SettingsServiceBlockingStub settingsService;
    private final ReportEventServiceGrpc.ReportEventServiceBlockingStub reportEvents;
    private final TeamDirectory directory;

    public ReportController(PerformanceServiceGrpc.PerformanceServiceBlockingStub performanceService,
                            SettingsServiceGrpc.SettingsServiceBlockingStub settingsService,
                            ReportEventServiceGrpc.ReportEventServiceBlockingStub reportEvents,
                            TeamDirectory directory) {
        this.performanceService = performanceService;
        this.settingsService = settingsService;
        this.reportEvents = reportEvents;
        this.directory = directory;
    }

    @Operation(
            summary = "The monthly figures, as JSON",
            description = """
                    One row per person you may see: what they did on the phone that month, and what \
                    came of it on the recruiting board. This is the data behind the PDF — use it to \
                    render the same figures on screen.

                    ## Choosing the month

                    `?month=2026-08` reports on that calendar month. Omit it for the month that has \
                    just finished, which is what a monthly report almost always means.

                    ## Where each half comes from

                    **Phone figures** come from this system's own database, not from RingCentral. \
                    Every shift is written down as it finishes, so a month is a single indexed \
                    query rather than minutes of throttled paging. `recordedDays` is how many \
                    shifts actually have a stored row — fewer than the month means the snapshot \
                    was not running then, **not** that nobody worked.

                    **Hiring figures** come from the monday board. `hiredInPeriod`, \
                    `stillActiveFromPeriod` and `terminatedInPeriod` are for the month reported. \
                    `hiredToDate`, `activeNow` and `terminatedToDate` are all-time, for context.

                    ## Targets

                    Each target is the daily standard multiplied by `recordedDays`, not by the \
                    length of the month. Somebody with fourteen stored shifts is measured against \
                    fourteen shifts' worth, which is the only comparison that means anything when \
                    somebody was on leave.

                    ## When the board cannot find somebody

                    `boardMatched: false` means their `mondayName` is empty or matches no row. \
                    Every hiring figure is then a zero meaning **unknown**, not "hired nobody". \
                    Show a dash.

                    ## One company at a time

                    A report covers exactly one MS. `?entity=JM` or `?entity=BP` chooses; without \
                    it you get your own company, and JM if you can see both. Everybody in the \
                    answer is from that company and nobody else appears — the two run separate \
                    boards and separate phone systems, so a combined table would rank people \
                    against figures that are not comparable.

                    A person whose role grant carries no company belongs to no MS and is in no \
                    report. An HR must be granted their role **with** a company to be reported \
                    on.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One row per person in scope."),
            @ApiResponse(responseCode = "400", description = "`month` is not `YYYY-MM`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "No performance permission.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping("/monthly")
    @PreAuthorize("hasAnyAuthority('performance:read','performance:readTeam','performance:readAll')")
    public ReportViews.TeamReportView monthly(
            @Parameter(description = "The month to report on, `YYYY-MM`. Defaults to last month.",
                       example = "2026-08")
            @RequestParam(required = false)
            @Pattern(regexp = "\\d{4}-\\d{2}", message = "month must look like 2026-08") String month,

            @Parameter(description = "One recruiter only, by account id. Omit for everybody you "
                                     + "may see.",
                       example = "3d5a399e-2df0-4c06-9e74-098dae3243c9")
            @RequestParam(required = false) String userId,

            @Parameter(description = "Which MS to report on. Defaults to your own, and to JM when "
                                     + "you can see both.",
                       example = "JM", schema = @Schema(allowableValues = {"JM", "BP"}))
            @RequestParam(required = false) String entity,

            @AuthenticationPrincipal AuthPrincipal principal) {

        YearMonth period = monthOf(month);
        Entity company = companyFor(entity, principal);
        Map<String, User> visible = only(
                TeamDirectory.inCompany(directory.visibleTo(principal), company), userId, company);
        return ReportViews.of(report(visible, period), visible);
    }

    @Operation(
            summary = "The monthly report, as a PDF",
            description = """
                    The document itself: a title block with a reference, a summary, a table, bar \
                    charts per recruiter against the standard, and a full written analysis. Laid \
                    out as a document of record — it is meant to be filed and forwarded.

                    ## One MS per report

                    A report covers exactly one company. `?entity=JM` or `?entity=BP` chooses; \
                    without it you get your own, and JM if you can see both. This is the whole \
                    point of the parameter: the analysis ranks people against each other and \
                    concludes something about the state of one MS, and JM and BP share no board, \
                    no phone system and no manager — one document covering both would compare \
                    figures that are not comparable.

                    ## What it contains

                    Every recruiter in that MS, the HR lead included, for the month chosen: calls, \
                    talk time and messages against the target; drivers hired that month, how many \
                    are still driving, how many were terminated; the recruiting standard for the \
                    quarter of their own tenure, both for the month and for the quarter so far; \
                    and the all-time figures for context.

                    The analysis is written by the language model from exactly those numbers, as a \
                    management report: a scorecard, a person-by-person assessment, activity \
                    against results, retention, the lead as a lead, the condition of the MS, an \
                    action plan and a conclusion. If no model is configured or it cannot be \
                    reached, **the PDF is still produced** — the assessment section says why it is \
                    missing instead. A report that fails entirely because a third party is down \
                    would be the wrong trade.

                    ## Notification

                    A successful generation writes **"Report generated successfully"** to the \
                    caller's own notifications, and to nobody else's.

                    Returns `application/pdf` with a `Content-Disposition` naming the file. Point \
                    a browser at it, or read the filename from the header.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The PDF.",
                    content = @Content(mediaType = "application/pdf")),
            @ApiResponse(responseCode = "400", description = "`month` is not `YYYY-MM`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "No performance permission.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/monthly/pdf")
    @PreAuthorize("hasAnyAuthority('performance:read','performance:readTeam','performance:readAll')")
    public ResponseEntity<byte[]> pdf(
            @Parameter(description = "The month to report on, `YYYY-MM`. Defaults to last month.",
                       example = "2026-08")
            @RequestParam(required = false)
            @Pattern(regexp = "\\d{4}-\\d{2}", message = "month must look like 2026-08") String month,

            @Parameter(description = "One recruiter only, by account id. Omit for everybody you "
                                     + "may see. This is how a lead reports on a single HR.",
                       example = "3d5a399e-2df0-4c06-9e74-098dae3243c9")
            @RequestParam(required = false) String userId,

            @Parameter(description = "Something to steer the assessment towards. Optional.",
                       example = "Focus on retention")
            @RequestParam(required = false) @Size(max = 500) String question,

            @Parameter(description = "Which MS to report on. Defaults to your own, and to JM when "
                                     + "you can see both.",
                       example = "JM", schema = @Schema(allowableValues = {"JM", "BP"}))
            @RequestParam(required = false) String entity,

            @AuthenticationPrincipal AuthPrincipal principal) {

        YearMonth period = monthOf(month);
        Entity company = companyFor(entity, principal);
        Map<String, User> visible = only(
                TeamDirectory.inCompany(directory.visibleTo(principal), company), userId, company);
        if (visible.isEmpty()) {
            throw new java.util.NoSuchElementException(
                    "Nobody in " + nameOf(company) + " has a RingCentral number, so there is "
                    + "nothing to report on");
        }
        List<ReportSubject> subjects = subjects(visible);
        ActivityStandard standard = standard();

        // The rows are fetched first and separately from the assessment, so that a model outage
        // costs the reader a paragraph rather than the whole document.
        TeamReportResponse report = report(visible, period);

        String assessment = null;
        String assessmentNote = null;
        try {
            FeedbackRequest.Builder request = FeedbackRequest.newBuilder()
                    .addAllSubjects(subjects)
                    .setFromDate(period.atDay(1).toString())
                    .setToDate(period.atEndOfMonth().toString())
                    .setStandard(standard)
                    .setQuestion(question == null ? "" : question);
            FeedbackResponse feedback = performanceService.generateFeedback(request.build());
            assessment = feedback.getFeedback();
        } catch (RuntimeException e) {
            assessmentNote = "A written assessment could not be produced for this report: "
                             + reasonFrom(e);
            log.warn("Report {} generated without an assessment: {}", period, e.toString());
        }

        byte[] pdf = MonthlyReportPdf.build(
                period,
                principal.username(),
                // A single-recruiter report says whose it is on the cover, because that is the
                // first thing anybody opening it needs to know.
                visible.size() == 1
                        ? visible.values().iterator().next().getFullName()
                        : nameOf(company),
                nameOf(company),
                report.getRowsList(),
                report.getStandard(),
                assessment,
                assessmentNote);

        String filename = visible.size() == 1
                ? "drenix-%s-report-%s-%s.pdf".formatted(nameOf(company).toLowerCase(Locale.ROOT),
                        slug(visible.values().iterator().next().getUsername()), period)
                : "drenix-%s-recruiting-report-%s.pdf".formatted(
                        nameOf(company).toLowerCase(Locale.ROOT), period);
        announce(period, report.getRowsCount(), filename);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /**
     * Writes "Report generated successfully" to the caller's own notifications.
     *
     * <p>Never allowed to fail the request. The file is already built and the reader is waiting
     * for it; losing the download because the notification service is restarting would be an
     * absurd trade.
     */
    private void announce(YearMonth month, int subjects, String filename) {
        try {
            reportEvents.reportGenerated(ReportGeneratedRequest.newBuilder()
                    .setMonth(month.toString())
                    .setSubjects(subjects)
                    .setFilename(filename)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Report {} was generated but could not be announced: {}", month, e.toString());
        }
    }

    /** The description from a gRPC failure, or the exception's own message. */
    private static String reasonFrom(RuntimeException e) {
        if (e instanceof io.grpc.StatusRuntimeException status
                && status.getStatus().getDescription() != null) {
            return status.getStatus().getDescription();
        }
        return "the language model was unavailable.";
    }

    private TeamReportResponse report(Map<String, User> visible, YearMonth month) {
        return performanceService.getTeamReport(TeamReportRequest.newBuilder()
                .addAllSubjects(subjects(visible))
                .setFromDate(month.atDay(1).toString())
                .setToDate(month.atEndOfMonth().toString())
                .setStandard(standard())
                .build());
    }

    /**
     * The month asked for, or the one that has just finished.
     *
     * <p>Last month rather than this one: a report for a month still running is half a report, and
     * "the monthly report" almost always means the one that is complete.
     */
    private static YearMonth monthOf(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(LocalDate.now()).minusMonths(1);
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("month must look like 2026-08");
        }
    }

    /** A filename fragment that survives being emailed around. */
    private static String slug(String value) {
        return value == null ? "report" : value.replaceAll("[^A-Za-z0-9]+", "-");
    }

    /**
     * Which MS this report is about.
     *
     * <p>Exactly one, always. A monthly report ranks people against each other and concludes
     * something about the state of a company, and JM and BP share no board, no phone system and no
     * manager — so a single document covering both would compare figures that are not comparable
     * and would show each lead the other company's team.
     *
     * <p>Unasked, it is the caller's own company. Somebody who can see both — an owner, the CEO —
     * gets JM by default and asks for {@code ?entity=BP} to get the other one, which is one report
     * each rather than one report of both.
     */
    private static Entity companyFor(String requested, AuthPrincipal principal) {
        if (requested != null && !requested.isBlank()) {
            Entity asked = switch (requested.trim().toUpperCase(Locale.ROOT)) {
                case "JM" -> Entity.ENTITY_JM;
                case "BP" -> Entity.ENTITY_BP;
                default -> throw new BadRequestException("entity must be JM or BP");
            };
            if (!principal.canActOn(scopeOf(asked)) && !global(principal)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "You have no access to " + requested.trim().toUpperCase(Locale.ROOT));
            }
            return asked;
        }
        if (global(principal) || principal.canActOn(AuthPrincipal.EntityScope.JM)) {
            return Entity.ENTITY_JM;
        }
        if (principal.canActOn(AuthPrincipal.EntityScope.BP)) {
            return Entity.ENTITY_BP;
        }
        throw new org.springframework.security.access.AccessDeniedException(
                "Your account is not attached to a company, so there is no report to run");
    }

    /** A grant with no company covers every company. An empty scope set is the same thing. */
    private static boolean global(AuthPrincipal principal) {
        return principal.entityScopes().isEmpty()
               || principal.entityScopes().contains(AuthPrincipal.EntityScope.GLOBAL);
    }

    private static AuthPrincipal.EntityScope scopeOf(Entity entity) {
        return entity == Entity.ENTITY_BP
                ? AuthPrincipal.EntityScope.BP
                : AuthPrincipal.EntityScope.JM;
    }

    private static String nameOf(Entity entity) {
        return entity.name().replace("ENTITY_", "");
    }

    /**
     * Narrows the answer to one person, when one was named.
     *
     * <p>Filtered from what the caller may already see rather than looked up directly, so asking
     * for somebody outside your own company answers 404 — the same as an id that does not exist.
     * An id cannot be used to find out who works in the other company.
     *
     * <p>It is filtered from the <em>company-scoped</em> set, so an owner asking for a BP recruiter
     * without {@code ?entity=BP} is told nobody by that id is in this report rather than quietly
     * being handed a BP person under a JM heading.
     */
    private static Map<String, User> only(Map<String, User> visible, String userId, Entity company) {
        if (userId == null || userId.isBlank()) {
            return visible;
        }
        User one = visible.get(userId.trim());
        if (one == null) {
            throw new java.util.NoSuchElementException(
                    "Nobody with that id is in the " + nameOf(company) + " report. They may work "
                    + "for the other company, have no RingCentral number, or not exist");
        }
        return Map.of(one.getId(), one);
    }

    /** Everything performance-service needs to find somebody in the two external systems. */
    private static List<ReportSubject> subjects(Map<String, User> visible) {
        return visible.values().stream()
                .map(user -> ReportSubject.newBuilder()
                        .setId(user.getId())
                        .setName(user.getFullName().isBlank()
                                ? user.getUsername()
                                : user.getFullName())
                        .addAllRingCentralPhones(user.getRingCentralPhonesList())
                        .setEntity(TeamDirectory.companyOf(user))
                        .setMondayName(user.getMondayName())
                        .setPosition(TeamDirectory.positionOf(user))
                        .setEmploymentStartDate(user.getEmploymentStartDate())
                        .build())
                .toList();
    }

    /**
     * The editable standard, read here rather than downstream.
     *
     * <p>performance-service holds no database of its own for it; the identity database does, and
     * this is the only place that already talks to both.
     */
    private ActivityStandard standard() {
        ActivityStandardSettings settings =
                settingsService.getActivityStandard(Empty.getDefaultInstance());
        return ActivityStandard.newBuilder()
                .setCallsPerDay(settings.getCallsPerDay())
                .setTalkSecondsPerDay(settings.getTalkSecondsPerDay())
                .setSmsSentPerDay(settings.getSmsSentPerDay())
                .setShiftMinutes(settings.getShiftMinutes())
                .build();
    }
}
