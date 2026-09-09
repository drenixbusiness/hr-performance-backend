package uz.drenix.identity.performance.grpc;

import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import org.springframework.stereotype.Service;
import java.time.Instant;
import uz.drenix.identity.grpc.v1.ActivityHistoryRequest;
import uz.drenix.identity.grpc.v1.ActivityHistoryResponse;
import uz.drenix.identity.grpc.v1.ActivityStandard;
import uz.drenix.identity.grpc.v1.FeedbackRequest;
import uz.drenix.identity.grpc.v1.FeedbackResponse;
import uz.drenix.identity.grpc.v1.TeamReportRequest;
import uz.drenix.identity.grpc.v1.TeamReportResponse;
import uz.drenix.identity.grpc.v1.TeamReportRow;
import uz.drenix.identity.grpc.v1.AgentActivity;
import uz.drenix.identity.grpc.v1.DailyActivity;
import uz.drenix.identity.grpc.v1.DataIssue;
import uz.drenix.identity.grpc.v1.HrActivityRequest;
import uz.drenix.identity.grpc.v1.HrActivityResponse;
import uz.drenix.identity.grpc.v1.HrPerformanceRequest;
import uz.drenix.identity.grpc.v1.HourPoint;
import uz.drenix.identity.grpc.v1.HrPerformanceResponse;
import uz.drenix.identity.grpc.v1.MonthlyPoint;
import uz.drenix.identity.grpc.v1.PerformanceServiceGrpc;
import uz.drenix.identity.grpc.v1.QuarterResult;
import uz.drenix.identity.grpc.v1.RecruiterPerformance;
import uz.drenix.identity.performance.metric.DataIssueDetector;
import uz.drenix.identity.performance.metric.PerformanceCalculator;
import uz.drenix.identity.performance.metric.RecruitingStandard;
import uz.drenix.identity.performance.monday.MondayBoardClient;
import uz.drenix.identity.performance.monday.DriverRecord;
import uz.drenix.identity.performance.ai.FeedbackWriter;
import uz.drenix.identity.performance.report.TeamReportService;
import uz.drenix.identity.performance.store.ActivityHistoryService;
import uz.drenix.identity.performance.ringcentral.ActivityService;
import uz.drenix.identity.performance.ringcentral.RingCentralRateLimitedException;
import uz.drenix.platform.security.GrpcAuthContext;
import uz.drenix.platform.security.Permissions;

/**
 * The one RPC this service offers.
 *
 * <p>Read-only, and it owns no data: every number is derived from the monday.com board on the way
 * through. Nothing here writes back to monday, which is deliberate — the board is where recruiting
 * actually happens, and a reporting service that could edit it would eventually be asked to.
 */
@Service
public class PerformanceGrpcService extends PerformanceServiceGrpc.PerformanceServiceImplBase {

    private final MondayBoardClient board;
    private final PerformanceCalculator calculator;
    private final DataIssueDetector issueDetector;
    private final Clock clock;
    private final ActivityService activity;
    private final ActivityHistoryService history;
    private final TeamReportService reports;
    private final FeedbackWriter feedbackWriter;

    public PerformanceGrpcService(MondayBoardClient board,
                                  PerformanceCalculator calculator,
                                  DataIssueDetector issueDetector,
                                  ActivityService activity,
                                  ActivityHistoryService history,
                                  TeamReportService reports,
                                  FeedbackWriter feedbackWriter,
                                  Clock clock) {
        this.board = board;
        this.calculator = calculator;
        this.issueDetector = issueDetector;
        this.activity = activity;
        this.history = history;
        this.reports = reports;
        this.feedbackWriter = feedbackWriter;
        this.clock = clock;
    }

    @Override
    public void getHrPerformance(HrPerformanceRequest request,
                                 StreamObserver<HrPerformanceResponse> observer) {
        GrpcAuthContext.require(Permissions.PERFORMANCE_READ);

        LocalDate today = LocalDate.now(clock);
        int year = request.getYear() == 0 ? Year.now(clock).getValue() : request.getYear();
        String only = request.getRecruiter().isBlank() ? null : request.getRecruiter().trim();

        // Unspecified means every board this service knows about. JM and BP keep separate boards,
        // and a caller who may see both should get one chart covering both rather than have to
        // ask twice and stitch the answers together.
        List<String> entities = request.getEntity() == uz.drenix.identity.grpc.v1.Entity.ENTITY_UNSPECIFIED
                ? List.copyOf(board.entities())
                : List.of(entityName(request.getEntity()));

        HrPerformanceResponse.Builder response = HrPerformanceResponse.newBuilder().setYear(year);
        long oldestRead = Long.MAX_VALUE;

        for (String entity : entities) {
            MondayBoardClient.Snapshot snapshot = board.snapshot(entity);
            // The oldest of the boards read, not the newest: a caller deciding whether the chart
            // is fresh enough should be told about the stalest part of it.
            oldestRead = Math.min(oldestRead, snapshot.readAt().getEpochSecond());

            List<DriverRecord> drivers = only == null
                    ? snapshot.drivers()
                    : snapshot.drivers().stream()
                            .filter(d -> d.recruiter() != null
                                         && d.recruiter().equalsIgnoreCase(only))
                            .toList();

            uz.drenix.identity.grpc.v1.Entity proto = protoEntity(entity);
            for (PerformanceCalculator.RecruiterChart chart
                    : calculator.calculate(drivers, year, today)) {
                response.addRecruiters(toProto(chart).toBuilder().setEntity(proto).build());
            }
            for (DataIssueDetector.Issue issue : issueDetector.detect(drivers)) {
                response.addIssues(DataIssue.newBuilder()
                        .setEntity(proto)
                        .setItemName(nullToEmpty(issue.itemName()))
                        .setRecruiter(nullToEmpty(issue.recruiter()))
                        .setKind(issue.kind().name())
                        .setProblem(issue.problem())
                        .build());
            }
        }

        response.setGeneratedAtEpochSeconds(
                oldestRead == Long.MAX_VALUE ? Instant.now(clock).getEpochSecond() : oldestRead);
        observer.onNext(response.build());
        observer.onCompleted();
    }

    @Override
    public void getHrActivity(HrActivityRequest request,
                              StreamObserver<HrActivityResponse> observer) {
        // The gateway has already decided whose numbers these are; this service only counts.
        // notification-service also calls this, on a schedule and with no user token: the RPC
        // allowlist vouches for that peer, and it still only gets the numbers it named.
        GrpcAuthContext.requirePermissionOrPeer(Permissions.PERFORMANCE_READ);

        LocalDate to = request.getToDate().isBlank()
                ? LocalDate.now(clock.withZone(activity.reportingZone()))
                : LocalDate.parse(request.getToDate());
        // Today and yesterday. Two days is what a shift lead actually looks at, and RingCentral
        // allows ten "heavy" requests a minute, so a wide default would make every page load slow
        // for a range almost nobody wanted.
        LocalDate from = request.getFromDate().isBlank()
                ? to.minusDays(1)
                : LocalDate.parse(request.getFromDate());

        LocalTime fromTime = time(request.getFromTime(), "fromTime");
        LocalTime toTime = time(request.getToTime(), "toTime");

        ActivityService.Standard standard = request.hasStandard()
                ? new ActivityService.Standard(
                        request.getStandard().getCallsPerDay(),
                        request.getStandard().getTalkSecondsPerDay(),
                        request.getStandard().getSmsSentPerDay(),
                        Duration.ofMinutes(request.getStandard().getShiftMinutes()))
                : activity.configuredStandard();

        ActivityService.Report report;
        try {
            report = activity.activity(selectors(request), from, to, fromTime, toTime);
        } catch (RingCentralRateLimitedException e) {
            // RESOURCE_EXHAUSTED rather than INTERNAL: nothing is broken, the account has simply
            // spent its minute. The gateway turns this into a 429 with a Retry-After so the client
            // knows to come back rather than to report a fault.
            throw io.grpc.Status.RESOURCE_EXHAUSTED
                    .withDescription("RingCentral is busy. Try again in "
                                     + Math.max(e.retryAfter().toSeconds(), 1) + " seconds.")
                    .asRuntimeException();
        }

        ActivityService.Standard windowStandard = scaled(standard, report.windowLength());

        HrActivityResponse.Builder response = HrActivityResponse.newBuilder()
                .setFromDate(from.toString())
                .setToDate(to.toString())
                .setFromTime(request.getFromTime())
                .setToTime(request.getToTime())
                .setZone(activity.reportingZone().getId())
                .setWindowMinutes((int) report.windowLength().toMinutes())
                .setGeneratedAtEpochSeconds(Instant.now(clock).getEpochSecond())
                .setStandard(toProto(standard))
                .setWindowStandard(toProto(windowStandard));

        for (ActivityService.AgentActivity agent : report.agents()) {
            response.addAgents(toProto(agent, windowStandard));
        }
        observer.onNext(response.build());
        observer.onCompleted();
    }

    /**
     * The standard scaled to the slice of a shift the caller asked about.
     *
     * <p>Straight-line proration: three hours of a nine-hour shift carries a third of the target.
     * It is not capped below, so a short window is judged on its own terms, but it <em>is</em>
     * capped at one whole shift — asking for a full 24 hours does not raise anybody's target,
     * because the standard is a day's work and not a rate per hour.
     */
    private static ActivityService.Standard scaled(ActivityService.Standard standard,
                                                   Duration window) {
        long shiftSeconds = Math.max(standard.shift().toSeconds(), 1);
        long windowSeconds = Math.min(window.toSeconds(), shiftSeconds);
        if (windowSeconds == shiftSeconds) {
            return standard;
        }
        double factor = (double) windowSeconds / shiftSeconds;
        return new ActivityService.Standard(
                (int) Math.round(standard.callsPerDay() * factor),
                (int) Math.round(standard.talkSecondsPerDay() * factor),
                (int) Math.round(standard.smsSentPerDay() * factor),
                standard.shift());
    }

    /**
     * Who to report on, from either shape of the request.
     *
     * <p>The flat list is the old contract, one row per number. It is kept working by treating
     * each number as a group of one, which is exactly what it used to mean.
     */
    private static List<ActivityService.AgentKey> selectors(HrActivityRequest request) {
        if (request.getAgentsCount() > 0) {
            return request.getAgentsList().stream()
                    .map(agent -> new ActivityService.AgentKey(
                            agent.getId(),
                            agent.getRingCentralPhonesList(),
                            entityName(agent.getEntity())))
                    .toList();
        }
        return request.getRingCentralPhonesList().stream()
                .map(phone -> new ActivityService.AgentKey(phone, List.of(phone), ""))
                .toList();
    }

    /**
     * The bare company code from the proto enum, or empty when none was given.
     *
     * <p>Empty is not an error. It means "whichever account is configured as the default", which
     * is what an account whose role grants name no company gets.
     */
    /** The proto enum for a bare company code. */
    private static uz.drenix.identity.grpc.v1.Entity protoEntity(String entity) {
        try {
            return uz.drenix.identity.grpc.v1.Entity.valueOf("ENTITY_" + entity);
        } catch (IllegalArgumentException e) {
            return uz.drenix.identity.grpc.v1.Entity.ENTITY_UNSPECIFIED;
        }
    }

    private static String entityName(uz.drenix.identity.grpc.v1.Entity entity) {
        return entity == null || entity == uz.drenix.identity.grpc.v1.Entity.ENTITY_UNSPECIFIED
                ? ""
                : entity.name().replace("ENTITY_", "");
    }

    @Override
    public void getActivityHistory(ActivityHistoryRequest request,
                                   StreamObserver<ActivityHistoryResponse> observer) {
        GrpcAuthContext.requirePermissionOrPeer(Permissions.PERFORMANCE_READ);

        LocalDate to = request.getToDate().isBlank()
                ? LocalDate.now(clock.withZone(activity.reportingZone()))
                : LocalDate.parse(request.getToDate());
        LocalDate from = request.getFromDate().isBlank() ? to.minusDays(29)
                                                         : LocalDate.parse(request.getFromDate());
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("The end date is before the start date");
        }

        ActivityService.Standard standard = request.hasStandard()
                ? new ActivityService.Standard(
                        request.getStandard().getCallsPerDay(),
                        request.getStandard().getTalkSecondsPerDay(),
                        request.getStandard().getSmsSentPerDay(),
                        Duration.ofMinutes(request.getStandard().getShiftMinutes()))
                : activity.configuredStandard();

        ActivityHistoryResponse.Builder response = ActivityHistoryResponse.newBuilder()
                .setFromDate(from.toString())
                .setToDate(to.toString())
                .setZone(activity.reportingZone().getId())
                .setStandard(toProto(standard));

        history.read(request.getUserIdsList(), from, to, standard)
                .forEach(response::addAgents);

        observer.onNext(response.build());
        observer.onCompleted();
    }

    @Override
    public void getTeamReport(TeamReportRequest request,
                              StreamObserver<TeamReportResponse> observer) {
        GrpcAuthContext.requirePermissionOrPeer(Permissions.PERFORMANCE_READ);

        Range range = rangeOf(request.getFromDate(), request.getToDate());
        ActivityStandard standard = standardOf(request.hasStandard() ? request.getStandard() : null);

        observer.onNext(TeamReportResponse.newBuilder()
                .setFromDate(range.from().toString())
                .setToDate(range.to().toString())
                .setStandard(standard)
                .addAllRows(reports.rows(
                        request.getSubjectsList(), range.from(), range.to(), standard))
                .setGeneratedAtEpochSeconds(Instant.now(clock).getEpochSecond())
                .build());
        observer.onCompleted();
    }

    @Override
    public void generateFeedback(FeedbackRequest request,
                                 StreamObserver<FeedbackResponse> observer) {
        GrpcAuthContext.requirePermissionOrPeer(Permissions.PERFORMANCE_READ);

        Range range = rangeOf(request.getFromDate(), request.getToDate());
        ActivityStandard standard = standardOf(request.hasStandard() ? request.getStandard() : null);
        List<TeamReportRow> rows =
                reports.rows(request.getSubjectsList(), range.from(), range.to(), standard);

        // Written from exactly the rows returned alongside it, so the words and the numbers on
        // screen can never describe two different things.
        String feedback;
        try {
            feedback = feedbackWriter.write(rows, range.from().toString(), range.to().toString(),
                    standard, request.getQuestion());
        } catch (uz.drenix.identity.performance.ai.AiRejectedException e) {
            // FAILED_PRECONDITION, not UNAVAILABLE: nothing is broken and retrying will not help.
            // Somebody has to add credits or fix a key, and the message says which.
            throw io.grpc.Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .asRuntimeException();
        }

        observer.onNext(FeedbackResponse.newBuilder()
                .setFeedback(feedback)
                .setModel(feedbackWriter.model())
                .setSubjectsCovered(rows.size())
                .addAllRows(rows)
                .build());
        observer.onCompleted();
    }

    /** A resolved date range. Defaults to the last thirty days, which is the month-end question. */
    private record Range(LocalDate from, LocalDate to) {
    }

    private Range rangeOf(String fromDate, String toDate) {
        LocalDate to = toDate.isBlank()
                ? LocalDate.now(clock.withZone(activity.reportingZone()))
                : LocalDate.parse(toDate);
        LocalDate from = fromDate.isBlank() ? to.minusDays(29) : LocalDate.parse(fromDate);
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("The end date is before the start date");
        }
        return new Range(from, to);
    }

    private ActivityStandard standardOf(ActivityStandard supplied) {
        return supplied != null ? supplied : toProto(activity.configuredStandard());
    }

    private static LocalTime time(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must look like HH:MM, for example 18:00");
        }
    }

    private static ActivityStandard toProto(ActivityService.Standard standard) {
        return ActivityStandard.newBuilder()
                .setCallsPerDay(standard.callsPerDay())
                .setTalkSecondsPerDay(standard.talkSecondsPerDay())
                .setSmsSentPerDay(standard.smsSentPerDay())
                .setShiftMinutes((int) standard.shift().toMinutes())
                .build();
    }

    private static AgentActivity toProto(ActivityService.AgentActivity agent,
                                         ActivityService.Standard standard) {
        AgentActivity.Builder builder = AgentActivity.newBuilder()
                .setAgentId(nullToEmpty(agent.agentId()))
                .setRingCentralPhone(nullToEmpty(agent.ringCentralPhone()))
                .addAllRingCentralPhones(agent.ringCentralPhones())
                .addAllUnresolvedPhones(agent.unresolvedPhones())
                .setDisplayName(nullToEmpty(agent.displayName()))
                .setExtensionId(nullToEmpty(agent.extensionId()))
                .setTotalCalls(agent.totalCalls())
                .setTotalTalkSeconds(agent.totalTalkSeconds())
                .setTotalSmsSent(agent.totalSmsSent())
                .setActiveDays(agent.activeDays())
                .setExtensionUnresolved(agent.extensionUnresolved());

        for (ActivityService.DayPoint day : agent.days()) {
            DailyActivity.Builder daily = DailyActivity.newBuilder()
                    .setDate(day.date().toString())
                    .setCalls(day.calls())
                    .setTalkSeconds(day.talkSeconds())
                    .setSmsSent(day.smsSent())
                    .setCallsMet(day.calls() >= standard.callsPerDay())
                    .setTalkMet(day.talkSeconds() >= standard.talkSecondsPerDay())
                    .setSmsMet(day.smsSent() >= standard.smsSentPerDay());

            for (ActivityService.HourPoint hour : day.hours()) {
                daily.addHours(HourPoint.newBuilder()
                        .setHour(hour.hour().toString())
                        .setCalls(hour.calls())
                        .setTalkSeconds(hour.talkSeconds())
                        .setSmsSent(hour.smsSent())
                        .build());
            }
            builder.addDays(daily.build());
        }
        return builder.build();
    }

    private static RecruiterPerformance toProto(PerformanceCalculator.RecruiterChart chart) {
        RecruiterPerformance.Builder builder = RecruiterPerformance.newBuilder()
                .setRecruiter(chart.recruiter())
                .setStartedMonth(chart.startedMonth() == null ? "" : chart.startedMonth().toString())
                .setActiveNow(chart.activeNow())
                .setRequiredActiveAfterYear(RecruitingStandard.ACTIVE_AFTER_FIRST_YEAR)
                .setYearComplete(chart.yearComplete())
                .setYearTargetMet(chart.yearTargetMet());

        for (PerformanceCalculator.MonthPoint point : chart.months()) {
            builder.addMonths(MonthlyPoint.newBuilder()
                    .setMonth(point.month().toString())
                    .setHired(point.hired())
                    .setTerminated(point.terminated())
                    .setActive(point.active())
                    .setQuarter(point.quarter())
                    .setFuture(point.future())
                    .build());
        }
        for (PerformanceCalculator.QuarterOutcome outcome : chart.quarters()) {
            builder.addQuarters(QuarterResult.newBuilder()
                    .setQuarter(outcome.standard().quarter())
                    .setFirstMonth(outcome.firstMonth().toString())
                    .setLastMonth(outcome.lastMonth().toString())
                    .setRequiredHiresPerMonth(outcome.standard().hiresPerMonth())
                    .setRequiredHiresTotal(outcome.standard().hiresPerQuarter())
                    .setRequiredActive(outcome.standard().activeAtQuarterEnd())
                    .setActualHiresTotal(outcome.hires())
                    .setActualActiveAtEnd(outcome.activeAtEnd())
                    .setHiresMet(outcome.hiresMet())
                    .setActiveMet(outcome.activeMet())
                    .setComplete(outcome.complete())
                    .build());
        }
        return builder.build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
