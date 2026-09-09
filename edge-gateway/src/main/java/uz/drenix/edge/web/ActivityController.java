package uz.drenix.edge.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.drenix.edge.web.dto.ActivityStandardRequest;
import uz.drenix.edge.web.dto.ActivityViews;
import uz.drenix.identity.grpc.v1.ActivityHistoryRequest;
import uz.drenix.identity.grpc.v1.ActivityStandard;
import uz.drenix.identity.grpc.v1.ActivityStandardSettings;
import uz.drenix.identity.grpc.v1.AgentSelector;
import uz.drenix.identity.grpc.v1.HrActivityRequest;
import uz.drenix.identity.grpc.v1.HrActivityResponse;
import uz.drenix.identity.grpc.v1.ListUsersRequest;
import uz.drenix.identity.grpc.v1.PageRequest;
import uz.drenix.identity.grpc.v1.PerformanceServiceGrpc;
import uz.drenix.identity.grpc.v1.SettingsServiceGrpc;
import uz.drenix.identity.grpc.v1.UpdateActivityStandardRequest;
import uz.drenix.identity.grpc.v1.User;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;
import uz.drenix.platform.security.AuthPrincipal;
import uz.drenix.platform.security.Permissions;

/**
 * Call and SMS activity against the daily standard.
 *
 * <p>Who a caller may see is decided here rather than downstream, because this is where the token
 * and the user directory meet. performance-service is handed a list of phone numbers and reports
 * on exactly those — it has no idea who anybody is, and so cannot widen the answer.
 */
@RestController
@RequestMapping("/api/v1/activity")
@Tag(name = "Activity")
public class ActivityController {

    private final PerformanceServiceGrpc.PerformanceServiceBlockingStub performanceService;
    private final UserServiceGrpc.UserServiceBlockingStub userService;
    private final SettingsServiceGrpc.SettingsServiceBlockingStub settingsService;
    private final TeamDirectory directory;

    public ActivityController(PerformanceServiceGrpc.PerformanceServiceBlockingStub performanceService,
                              UserServiceGrpc.UserServiceBlockingStub userService,
                              SettingsServiceGrpc.SettingsServiceBlockingStub settingsService,
                              TeamDirectory directory) {
        this.performanceService = performanceService;
        this.userService = userService;
        this.settingsService = settingsService;
        this.directory = directory;
    }

    @Operation(
            summary = "Call and SMS activity against the standard",
            description = """
                    One row per person per shift: calls made, time on the phone, messages sent, \
                    each marked against the target.

                    ## Who you see

                    | Permission | Scope | Held by |
                    |---|---|---|
                    | `performance:read` | yourself only | HR |
                    | `performance:readTeam` | everyone in your own company | HR_LEAD |
                    | `performance:readAll` | the whole organisation | CEO, SUPER_ADMIN |

                    Company means the `entity` on your role grant — `HR_LEAD@JM` sees the JM \
                    recruiters. The widest permission you hold wins; you never need to say which \
                    one to use.

                    ## The range

                    Both dates are optional: `to` defaults to **today** and `from` to \
                    **yesterday**, so a bare call answers "today and yesterday".

                    **At most 7 days.** This endpoint reads the RingCentral call log live, which \
                    is the only way to see today or to answer an hour window, and RingCentral \
                    allows ten requests a minute per company. A month of one company is twenty \
                    thousand records fetched a page at a time; across both companies it overran \
                    the three-minute call deadline and answered `503 RingCentral could not be \
                    reached` — after three minutes, about a service that was working perfectly \
                    well. A longer range is now refused immediately with a 400 that names the \
                    limit.

                    For anything longer use **`GET /api/v1/activity/history`**, which answers any \
                    range in milliseconds from the stored daily snapshots. It has no hour \
                    breakdown and no today — that is the trade.

                    ## Asking about a shift rather than a calendar day

                    Add `fromTime` and `toTime` (`HH:mm`, both or neither) to count only the hours \
                    somebody was working. When `toTime` is **not after** `fromTime`, the window \
                    runs past midnight:

                    ```
                    GET /api/v1/activity?fromTime=18:00&toTime=03:00
                    ```

                    That is one nine-hour night, and it is reported **under the date it started \
                    on**: the night of the 27th is a single row dated `2026-08-27`, not two half \
                    rows either side of midnight.

                    Give a clock window and every day also carries an `hours` array — one entry \
                    per clock hour in the window, quiet hours included, so a bar chart has no \
                    gaps. Without a window `hours` is empty, because a month of whole days would \
                    be 744 points per person and nobody asked for that.

                    Dates and hours are in the response's `zone`.

                    ## The standard

                    | Measure | Default | Counted as |
                    |---|---|---|
                    | `calls` | 125 | every attempt, inbound or outbound, connected or not |
                    | `talkSeconds` | 3600 (1 hour) | total time on calls in the window |
                    | `smsSent` | 150 | outbound messages only |

                    Received SMS is deliberately excluded: a reply is not work the recruiter did, \
                    and counting it would let a talkative candidate lift somebody's figures.

                    These targets are **editable** — see `GET` and `PUT \
                    /api/v1/activity/standard`. They describe one full shift, whose length is \
                    `shiftMinutes` (540 by default, which is 18:00 to 03:00).

                    A shorter window is judged against a **scaled** target: three hours of a \
                    nine-hour shift carries a third of it. The response gives you both — \
                    `standard` is the full shift, and `windowStandard` is what `callsMet`, \
                    `talkMet` and `smsMet` were actually compared against. Asking for more than a \
                    shift never raises the bar; a shift's work is not a rate per hour.

                    ## Things to know before charting this

                    People with no `ringCentralPhone` on their account are not in the answer at \
                    all — there is nothing to match them by. Set it through \
                    `PATCH /api/v1/admin/users/{id}`.

                    A person whose extension never appears in the call log for the range comes back \
                    with `extensionUnresolved: true` and zeroes. That is not the same as a quiet \
                    week: it means they could not be found, and their SMS could not be looked up \
                    either.

                    `activeDays` counts the shifts anything happened on. Averaging totals over the \
                    whole range punishes people for leave; divide by this instead.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Daily activity for everyone in scope."),
            @ApiResponse(responseCode = "400",
                    description = "Dates are not ISO-8601, the range is backwards, or it is longer "
                                  + "than 31 days.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403",
                    description = "The token holds none of the three performance permissions.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429",
                    description = "RingCentral is throttling this account (10 requests a minute). "
                                  + "The message says how long to wait; retry after that.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "RingCentral could not be reached.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping
    @PreAuthorize("hasAnyAuthority('performance:read','performance:readTeam','performance:readAll')")
    public ActivityViews.ActivityView activity(
            @Parameter(description = "First day, inclusive. Defaults to yesterday.",
                       example = "2026-08-27")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "Last day, inclusive. Defaults to today.", example = "2026-08-28")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @Parameter(description = "Start of the clock window, `HH:mm`. Give it together with "
                                     + "`toTime` or leave both out for whole days.",
                       example = "18:00")
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "HH:mm") LocalTime fromTime,

            @Parameter(description = "End of the clock window, `HH:mm`, exclusive. When it is not "
                                     + "after `fromTime` the window runs past midnight — `18:00` to "
                                     + "`03:00` is one nine-hour night reported under its start date.",
                       example = "03:00")
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "HH:mm") LocalTime toTime,

            @AuthenticationPrincipal AuthPrincipal principal) {

        Map<String, User> visible = directory.visibleTo(principal);
        guardRange(from, to);

        HrActivityRequest.Builder request = HrActivityRequest.newBuilder()
                // The standard is read here rather than downstream so that performance-service can
                // stay stateless: the database belongs to user-service, and this is the only place
                // that already talks to both.
                .setStandard(toProto(currentStandard()));
        // One selector per person, carrying every number they work. performance-service adds a
        // selector up into a single row, so a lead on two extensions is one recruiter and not two
        // half-recruiters, each shown missing every target.
        visible.forEach((id, user) -> request.addAgents(AgentSelector.newBuilder()
                .setId(id)
                .addAllRingCentralPhones(user.getRingCentralPhonesList())
                // Which company's RingCentral account to look them up in. JM and BP are separate
                // accounts, and a BP number searched for in JM is simply not there — the person
                // would come back with every figure at zero and no sign of the mistake.
                .setEntity(TeamDirectory.companyOf(user))
                .build()));
        if (from != null) {
            request.setFromDate(from.toString());
        }
        if (to != null) {
            request.setToDate(to.toString());
        }
        if (fromTime != null) {
            request.setFromTime(fromTime.toString());
        }
        if (toTime != null) {
            request.setToTime(toTime.toString());
        }

        HrActivityResponse response = performanceService.getHrActivity(request.build());
        return ActivityViews.of(response, visible);
    }

    @Operation(
            summary = "Stored activity, for any range",
            description = """
                    The same figures as `GET /api/v1/activity`, read from this system's own \
                    database instead of from RingCentral.

                    Every finished shift is written down as it happens, so a month is one indexed \
                    query rather than minutes of throttled paging. **Use this for anything longer \
                    than a fortnight** — the live endpoint refuses those, because reading them took \
                    longer than the call deadline and failed after three minutes.

                    ## What it cannot do

                    No hour breakdown, and no clock window: the snapshot stores one row per shift, \
                    not the call log. No today either, until today's shift has finished and been \
                    recorded. Those three are exactly what `GET /api/v1/activity` is for.

                    ## recordedDays

                    How many shifts actually have a stored row. Fewer than the range asked for \
                    means the snapshot was not running then — **not** that nobody worked. Divide \
                    totals by `recordedDays`, never by the length of the range.

                    ## The standard

                    Applied at read time, so raising or lowering it re-judges stored history rather \
                    than rewriting it. `daysAllTargetsMet` is counted against whatever the standard \
                    is now.

                    Who you see is decided by exactly the rule `GET /api/v1/activity` uses.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One row per person in scope."),
            @ApiResponse(responseCode = "400", description = "`from` is after `to`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "No performance permission.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping("/history")
    @PreAuthorize("hasAnyAuthority('performance:read','performance:readTeam','performance:readAll')")
    public ActivityViews.HistoryView history(
            @Parameter(description = "First shift date, inclusive. Defaults to 30 days back.",
                       example = "2026-08-01")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "Last shift date, inclusive. Defaults to today.",
                       example = "2026-08-31")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @AuthenticationPrincipal AuthPrincipal principal) {

        Map<String, User> visible = directory.visibleTo(principal);
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("from is after to");
        }

        ActivityHistoryRequest.Builder request = ActivityHistoryRequest.newBuilder()
                .addAllUserIds(visible.keySet())
                .setStandard(toProto(currentStandard()));
        if (from != null) {
            request.setFromDate(from.toString());
        }
        if (to != null) {
            request.setToDate(to.toString());
        }
        return ActivityViews.of(performanceService.getActivityHistory(request.build()), visible);
    }

    /**
     * How much live RingCentral reading one request may ask for.
     *
     * <p>This endpoint reads the call log itself, because that is the only way to answer an hour
     * window or to see today. Reading is slow and hard-limited: ten requests a minute per company
     * account, and a month of one company is twenty thousand records fetched a page at a time. A
     * whole month across both companies overran the three-minute call deadline and came back as
     * "RingCentral could not be reached" after three minutes of waiting — a false diagnosis of a
     * service that was working, delivered too late to be worth having.
     *
     * <p>So a long range is refused immediately and by name. The stored snapshots answer the same
     * question for any range in milliseconds, and the message says so rather than leaving the
     * caller to discover it.
     *
     * <p>Seven, measured rather than guessed. Fourteen days of one company alone took 64 seconds
     * to read — 11,551 records through a ten-per-minute limit — so a fortnight across both was two
     * minutes, inside the deadline and nowhere near acceptable for a screen somebody is waiting on.
     * A week completes in about a minute and covers what live reading is actually for: today, and
     * a shift window over the last few days.
     */
    private static final int MAX_LIVE_DAYS = 7;

    private static void guardRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            return;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_LIVE_DAYS) {
            throw new BadRequestException(
                    ("%d days is too long to read live from RingCentral, which allows ten requests "
                     + "a minute per company. Ask for %d days or fewer, or use "
                     + "GET /api/v1/activity/history, which answers any range instantly from the "
                     + "stored daily snapshots.")
                            .formatted(days, MAX_LIVE_DAYS));
        }
    }

    @Operation(
            summary = "Read the current standard",
            description = """
                    The targets every row of `GET /api/v1/activity` is judged against, plus who \
                    last changed them and when.

                    Readable by anyone who may read activity at all — a recruiter cannot make sense \
                    of their own numbers without knowing the bar.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The standard in force."),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "No performance permission.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping("/standard")
    @PreAuthorize("hasAnyAuthority('performance:read','performance:readTeam','performance:readAll',"
                  + "'performance:manageStandard')")
    public ActivityViews.StandardSettingsView standard() {
        return ActivityViews.of(currentStandard());
    }

    @Operation(
            summary = "Change the standard",
            description = """
                    Raise or lower the targets. Held by **HR_LEAD**, **CEO** and **SUPER_ADMIN** \
                    through `performance:manageStandard`; an ordinary HR can read the standard but \
                    not move it.

                    ## Full replacement

                    Send all four numbers. They are read together and mean nothing apart — raising \
                    the call target without saying how long a shift is leaves a standard that a \
                    part-day question cannot be answered against. `GET /api/v1/activity/standard` \
                    returns exactly this shape, so read it, change what you want and send it back.

                    ## What `shiftMinutes` does

                    It is the denominator for part-day questions. With `shiftMinutes: 540` and a \
                    target of 125 calls, asking for `fromTime=18:00&toTime=03:00` (nine hours) \
                    keeps the target at 125, while `fromTime=18:00&toTime=21:00` (three hours) \
                    lowers it to 42. Asking for more than a shift never raises it: the standard is \
                    a shift's work, not a rate per hour.

                    ## Example

                    ```json
                    {
                      "callsPerDay": 140,
                      "talkSecondsPerDay": 4200,
                      "smsSentPerDay": 150,
                      "shiftMinutes": 540
                    }
                    ```

                    The change is written to the audit log with the old and the new values, under \
                    `performance.updateStandard`. It takes effect on the next request; results \
                    already cached keep the counts but are re-judged, because the standard is \
                    applied at read time.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The standard after the change."),
            @ApiResponse(responseCode = "400", description = "A number is missing or out of range.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403",
                    description = "The token lacks `performance:manageStandard`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PutMapping("/standard")
    @PreAuthorize("hasAuthority('performance:manageStandard')")
    public ActivityViews.StandardSettingsView updateStandard(
            @Valid @RequestBody ActivityStandardRequest request) {

        return ActivityViews.of(settingsService.updateActivityStandard(
                UpdateActivityStandardRequest.newBuilder()
                        .setCallsPerDay(request.callsPerDay())
                        .setTalkSecondsPerDay(request.talkSecondsPerDay())
                        .setSmsSentPerDay(request.smsSentPerDay())
                        .setShiftMinutes(request.shiftMinutes())
                        .build()));
    }

    private ActivityStandardSettings currentStandard() {
        return settingsService.getActivityStandard(uz.drenix.identity.grpc.v1.Empty.getDefaultInstance());
    }

    private static ActivityStandard toProto(ActivityStandardSettings settings) {
        return ActivityStandard.newBuilder()
                .setCallsPerDay(settings.getCallsPerDay())
                .setTalkSecondsPerDay(settings.getTalkSecondsPerDay())
                .setSmsSentPerDay(settings.getSmsSentPerDay())
                .setShiftMinutes(settings.getShiftMinutes())
                .build();
    }
}
