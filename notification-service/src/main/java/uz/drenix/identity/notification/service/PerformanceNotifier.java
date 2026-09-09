package uz.drenix.identity.notification.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uz.drenix.identity.grpc.v1.ActivityStandard;
import uz.drenix.identity.grpc.v1.ActivityStandardSettings;
import uz.drenix.identity.grpc.v1.AgentActivity;
import uz.drenix.identity.grpc.v1.AgentSelector;
import uz.drenix.identity.grpc.v1.DailyActivity;
import uz.drenix.identity.grpc.v1.Empty;
import uz.drenix.identity.grpc.v1.HrActivityRequest;
import uz.drenix.identity.grpc.v1.HrActivityResponse;
import uz.drenix.identity.grpc.v1.PerformanceServiceGrpc;
import uz.drenix.identity.grpc.v1.SettingsServiceGrpc;
import uz.drenix.identity.grpc.v1.User;
import uz.drenix.identity.notification.client.Directory;
import uz.drenix.identity.notification.config.NotificationProperties;

/**
 * Tells a lead who fell short, once the shift they fell short in has finished.
 *
 * <p><b>Only finished shifts.</b> Judging a shift while it is still running would report everyone
 * as failing every target until the last hour of it, and a warning that is usually wrong is a
 * warning people learn to close without reading.
 *
 * <p>A miss goes to the person themselves and to the leads of their own company — never across
 * companies, and never to anybody else. A lead is in their own company, so a lead who misses is
 * told about themselves; otherwise they would be the one person nobody ever tells.
 */
@Service
public class PerformanceNotifier {

    private static final Logger log = LoggerFactory.getLogger(PerformanceNotifier.class);

    /** The name of this generator's row in {@code sync_state}: the last shift date judged. */
    private static final String CURSOR = "performance";

    private final PerformanceServiceGrpc.PerformanceServiceBlockingStub performance;
    private final SettingsServiceGrpc.SettingsServiceBlockingStub settings;
    private final Directory directory;
    private final Notifications notifications;
    private final SyncState syncState;
    private final NotificationProperties properties;

    public PerformanceNotifier(PerformanceServiceGrpc.PerformanceServiceBlockingStub performance,
                               SettingsServiceGrpc.SettingsServiceBlockingStub settings,
                               Directory directory,
                               Notifications notifications,
                               SyncState syncState,
                               NotificationProperties properties) {
        this.performance = performance;
        this.settings = settings;
        this.directory = directory;
        this.notifications = notifications;
        this.syncState = syncState;
        this.properties = properties;
    }

    /**
     * Hourly, not daily.
     *
     * <p>A daily cron that fires while the service happens to be restarting simply does not run
     * that day. Checking hourly and skipping shifts already judged gets the same one notification
     * per shift, and recovers on its own.
     */
    @Scheduled(initialDelayString = "${drenix.notification.performance-initial-delay:PT90S}",
               fixedDelayString = "${drenix.notification.performance-interval:PT1H}")
    public void check() {
        try {
            run();
        } catch (RuntimeException e) {
            log.warn("Performance check failed, will retry: {}", e.toString());
        }
    }

    /** Visible for a manual run. Returns how many notification rows were written. */
    public int run() {
        LocalDate lastJudged = lastJudged();
        LocalDate newest = newestFinishedShift();
        if (!lastJudged.isBefore(newest)) {
            return 0;
        }

        // Bounded catch-up: a service off for a month must not fill every inbox on the morning it
        // comes back, and RingCentral would refuse the traffic anyway.
        LocalDate from = newest.minusDays(properties.getMaxCatchUpDays() - 1L);
        LocalDate start = lastJudged.plusDays(1).isAfter(from) ? lastJudged.plusDays(1) : from;

        int written = 0;
        for (LocalDate shift = start; !shift.isAfter(newest); shift = shift.plusDays(1)) {
            written += judge(shift);
            syncState.write(CURSOR, shift.toString());
        }
        return written;
    }

    private int judge(LocalDate shift) {
        List<User> everyone = directory.everyone();
        List<User> measured = everyone.stream()
                .filter(u -> u.getRingCentralPhonesCount() > 0)
                .toList();
        if (measured.isEmpty()) {
            return 0;
        }

        ActivityStandardSettings standard = settings.getActivityStandard(Empty.getDefaultInstance());

        HrActivityRequest.Builder request = HrActivityRequest.newBuilder()
                .setFromDate(shift.toString())
                .setToDate(shift.toString())
                .setFromTime(properties.getShiftStart().toString())
                .setToTime(properties.getShiftEnd().toString())
                .setStandard(ActivityStandard.newBuilder()
                        .setCallsPerDay(standard.getCallsPerDay())
                        .setTalkSecondsPerDay(standard.getTalkSecondsPerDay())
                        .setSmsSentPerDay(standard.getSmsSentPerDay())
                        .setShiftMinutes(standard.getShiftMinutes())
                        .build());
        for (User user : measured) {
            request.addAgents(AgentSelector.newBuilder()
                    .setId(user.getId())
                    .addAllRingCentralPhones(user.getRingCentralPhonesList())
                    // JM and BP are separate RingCentral accounts. Without this every BP
                    // recruiter would be looked up in the JM account, come back at zero, and be
                    // reported to their lead as having missed every target.
                    .setEntity(Directory.companyOf(user))
                    .build());
        }

        HrActivityResponse response = performance.getHrActivity(request.build());
        Map<String, User> byId = new java.util.HashMap<>();
        measured.forEach(u -> byId.put(u.getId(), u));

        int written = 0;
        for (AgentActivity agent : response.getAgentsList()) {
            User subject = byId.get(agent.getAgentId());
            if (subject == null || agent.getDaysCount() == 0) {
                continue;
            }
            if (agent.getExtensionUnresolved()) {
                // Every figure is a zero meaning "unknown". Reporting that as a missed target
                // would accuse somebody of doing nothing on the strength of a lookup failure.
                continue;
            }
            DailyActivity day = agent.getDays(0);
            List<String> shortfalls = shortfalls(day, response.getWindowStandard());
            if (shortfalls.isEmpty()) {
                continue;
            }

            // The person themselves, and the leads of their company. Telling the person is the
            // point: a recruiter who is short of the bar can only act on it if somebody says so,
            // and hearing it from the system before hearing it from a lead is the kinder order.
            Set<String> to = new java.util.LinkedHashSet<>();
            to.add(subject.getId());
            directory.supervisorsOf(subject, everyone).forEach(u -> to.add(u.getId()));

            written += notifications.deliver(
                    draft(shift, subject, day, response.getWindowStandard(), shortfalls),
                    to.stream().map(UUID::fromString).toList());
        }
        log.info("Performance check for the shift of {}: {} notifications", shift, written);
        return written;
    }

    private static List<String> shortfalls(DailyActivity day, ActivityStandard standard) {
        List<String> missed = new ArrayList<>();
        if (!day.getCallsMet()) {
            missed.add(day.getCalls() + " of " + standard.getCallsPerDay() + " calls");
        }
        if (!day.getTalkMet()) {
            missed.add(minutes(day.getTalkSeconds()) + " of "
                       + minutes(standard.getTalkSecondsPerDay()) + " talking");
        }
        if (!day.getSmsMet()) {
            missed.add(day.getSmsSent() + " of " + standard.getSmsSentPerDay() + " messages");
        }
        return missed;
    }

    private Notifications.Draft draft(LocalDate shift, User subject, DailyActivity day,
                                      ActivityStandard standard, List<String> shortfalls) {
        String name = Directory.displayName(subject);
        String title = name + " missed the standard on " + shift;
        String body = String.join(", ", shortfalls) + ".";

        return new Notifications.Draft(
                Notifications.Kind.PERFORMANCE,
                // All three missed is a different conversation from one of three.
                shortfalls.size() == 3 ? Notifications.Severity.ALERT
                                       : Notifications.Severity.WARNING,
                title,
                body,
                UUID.fromString(subject.getId()),
                name,
                shiftEndsAt(shift).toInstant(),
                shift.toString(),
                "perf:" + shift + ":" + subject.getId());
    }

    /** The last shift whose end has already passed. */
    private LocalDate newestFinishedShift() {
        ZonedDateTime now = ZonedDateTime.now(properties.getZone());
        LocalDate candidate = now.toLocalDate();
        while (shiftEndsAt(candidate).isAfter(now)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    /**
     * When the shift that started on {@code day} ends.
     *
     * <p>The end time being no later than the start is how this system says "past midnight" —
     * 18:00 to 03:00 closes on the following morning.
     */
    private ZonedDateTime shiftEndsAt(LocalDate day) {
        LocalDate endDay = properties.getShiftEnd().isAfter(properties.getShiftStart())
                ? day
                : day.plusDays(1);
        return endDay.atTime(properties.getShiftEnd()).atZone(properties.getZone());
    }

    /**
     * The last shift already judged.
     *
     * <p>A first run judges only the most recent finished shift, not every shift on record: the
     * point is to tell somebody about last night, and RingCentral will not serve a month of
     * history quickly enough to be worth it.
     */
    private LocalDate lastJudged() {
        String stored = syncState.read(CURSOR, "");
        if (stored.isBlank()) {
            LocalDate start = newestFinishedShift().minusDays(1);
            syncState.write(CURSOR, start.toString());
            return start;
        }
        try {
            return LocalDate.parse(stored);
        } catch (RuntimeException e) {
            return newestFinishedShift().minusDays(1);
        }
    }

    private static String minutes(long seconds) {
        return Duration.ofSeconds(seconds).toMinutes() + "m";
    }
}
