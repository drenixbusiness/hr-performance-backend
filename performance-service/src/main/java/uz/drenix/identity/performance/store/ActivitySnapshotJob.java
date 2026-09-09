package uz.drenix.identity.performance.store;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.drenix.identity.grpc.v1.ListUsersRequest;
import uz.drenix.identity.grpc.v1.PageRequest;
import uz.drenix.identity.grpc.v1.RoleAssignment;
import uz.drenix.identity.grpc.v1.User;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;
import uz.drenix.identity.performance.ringcentral.ActivityService;
import uz.drenix.identity.performance.ringcentral.RingCentralProperties;

/**
 * Writes each shift's figures down as it happens.
 *
 * <p>RingCentral is the source, but it is not a source you can query. Ten requests a minute and a
 * thousand rows a page mean a month of call log takes minutes to walk — fine once a night, useless
 * when somebody is waiting for a report. So every pass records the two shifts that matter, today's
 * and yesterday's, and every question about a past range is answered from the table instead.
 *
 * <p><b>Today's shift is recorded while it is still running</b> and refreshed on each pass, so the
 * evening is visible as it happens rather than appearing at three in the morning. The row is
 * marked {@code final} once the shift has ended and the counts can no longer move.
 *
 * <p><b>Counts only.</b> No target is stored: the standard is editable, and a stored verdict would
 * freeze last month against a bar that has since changed. Judging happens when the figures are
 * read.
 */
@Service
public class ActivitySnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(ActivitySnapshotJob.class);

    private final UserServiceGrpc.UserServiceBlockingStub userService;
    private final ActivityService activity;
    private final DailyActivityRepository store;
    private final RingCentralProperties properties;

    /**
     * How many days back a pass will look. Two is the steady state — today and yesterday — and
     * more only fills gaps, so raising it costs nothing once the table has caught up.
     */
    private final int backfillDays;

    public ActivitySnapshotJob(UserServiceGrpc.UserServiceBlockingStub userService,
                               ActivityService activity,
                               DailyActivityRepository store,
                               RingCentralProperties properties,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${drenix.snapshot.days:7}") int backfillDays) {
        this.userService = userService;
        this.activity = activity;
        this.store = store;
        this.properties = properties;
        this.backfillDays = Math.max(backfillDays, 2);
    }

    /**
     * Hourly.
     *
     * <p>Not a nightly cron: one that fires while the service happens to be restarting simply does
     * not run that night, and the day is lost for good — RingCentral will still have it, but
     * nothing would go back for it. Hourly costs a handful of requests and is self-healing.
     */
    @Scheduled(initialDelayString = "${drenix.snapshot.initial-delay:PT2M}",
               fixedDelayString = "${drenix.snapshot.interval:PT1H}")
    public void snapshot() {
        try {
            run();
        } catch (RuntimeException e) {
            // Never let the schedule die. RingCentral throttling and a restarting user-service
            // both surface here, and both fix themselves.
            log.warn("Activity snapshot failed, will retry: {}", e.toString());
        }
    }

    /** Visible for a manual run. Returns how many rows were written or refreshed. */
    public int run() {
        List<User> measured = everyone().stream()
                .filter(u -> u.getRingCentralPhonesCount() > 0)
                .toList();
        if (measured.isEmpty()) {
            return 0;
        }

        int written = 0;
        for (LocalDate shift : shiftsToRecord(measured.size())) {
            written += record(shift, measured);
        }
        return written;
    }

    /**
     * The shifts worth recording on this pass.
     *
     * <p>Today's and yesterday's always, because today's is still moving and yesterday's covers
     * the case where the service was down when it ended. Further back only when nothing is stored
     * yet: a fresh deployment would otherwise have no history at all until a month had passed,
     * and the report it exists to produce would be empty for that month.
     *
     * <p>A day that is already recorded as final is skipped. It cannot change, and re-reading it
     * would spend the RingCentral budget on an answer already in the table.
     *
     * <p>A shift that has not started yet is skipped rather than stored as a row of zeroes, which
     * would read as somebody who did nothing.
     */
    private List<LocalDate> shiftsToRecord(int people) {
        ZonedDateTime now = ZonedDateTime.now(properties.getZone());
        LocalDate today = now.toLocalDate();

        List<LocalDate> shifts = new ArrayList<>();
        for (int back = backfillDays - 1; back >= 0; back--) {
            LocalDate day = today.minusDays(back);
            if (day.atTime(properties.getShiftStart()).atZone(properties.getZone()).isAfter(now)) {
                continue;
            }
            // Today and yesterday are always refreshed; anything older only if it is missing.
            if (back > 1 && alreadyFinal(day, people)) {
                continue;
            }
            shifts.add(day);
        }
        return shifts;
    }

    private boolean alreadyFinal(LocalDate day, int people) {
        List<DailyActivityEntity> stored = store.onDate(day);
        return stored.size() >= people && stored.stream().allMatch(DailyActivityEntity::isShiftFinished);
    }

    @Transactional
    protected int record(LocalDate shift, List<User> measured) {
        List<ActivityService.AgentKey> agents = measured.stream()
                .map(u -> new ActivityService.AgentKey(
                        u.getId(), u.getRingCentralPhonesList(), companyOf(u)))
                .toList();

        ActivityService.Report report = activity.activity(
                agents, shift, shift, properties.getShiftStart(), properties.getShiftEnd());

        Map<DailyActivityEntity.Key, DailyActivityEntity> existing = new HashMap<>();
        store.onDate(shift).forEach(row -> existing.put(row.getId(), row));

        boolean finished = shiftEndsAt(shift).isBefore(ZonedDateTime.now(properties.getZone()));
        Map<String, User> byId = new HashMap<>();
        measured.forEach(u -> byId.put(u.getId(), u));

        List<DailyActivityEntity> rows = new ArrayList<>();
        for (ActivityService.AgentActivity agent : report.agents()) {
            User user = byId.get(agent.agentId());
            if (user == null || agent.days().isEmpty()) {
                continue;
            }
            if (agent.extensionUnresolved()) {
                // Every figure is a zero meaning "unknown". Storing that would turn a lookup
                // failure into a permanent record of somebody having done nothing.
                continue;
            }

            ActivityService.DayPoint day = agent.days().getFirst();
            DailyActivityEntity.Key key =
                    new DailyActivityEntity.Key(UUID.fromString(user.getId()), shift);
            DailyActivityEntity row = existing.get(key);
            if (row == null) {
                row = new DailyActivityEntity(key, companyOf(user));
            }
            row.setEntity(companyOf(user));
            row.setCalls(day.calls());
            row.setTalkSeconds(day.talkSeconds());
            row.setSmsSent(day.smsSent());
            row.setPhones(String.join(",", agent.ringCentralPhones()));
            row.setShiftFinished(finished);
            row.setRecordedAt(java.time.Instant.now());
            rows.add(row);
        }

        store.saveAll(rows);
        log.info("Snapshot of the {} shift: {} people recorded ({})",
                shift, rows.size(), finished ? "final" : "still running");
        return rows.size();
    }

    private ZonedDateTime shiftEndsAt(LocalDate day) {
        LocalDate endDay = properties.getShiftEnd().isAfter(properties.getShiftStart())
                ? day
                : day.plusDays(1);
        return endDay.atTime(properties.getShiftEnd()).atZone(properties.getZone());
    }

    /** The bare company code from a person's first company-scoped grant. */
    static String companyOf(User user) {
        for (RoleAssignment role : user.getRolesList()) {
            if (role.getEntity() != uz.drenix.identity.grpc.v1.Entity.ENTITY_UNSPECIFIED
                    && role.getEntity() != uz.drenix.identity.grpc.v1.Entity.UNRECOGNIZED) {
                return role.getEntity().name().replace("ENTITY_", "");
            }
        }
        return "";
    }

    /** Every account, walked page by page. */
    private List<User> everyone() {
        List<User> users = new ArrayList<>();
        String cursor = "";
        // A hard stop rather than while(true): a cursor that never empties would loop forever.
        for (int page = 0; page < 100; page++) {
            var response = userService.listUsers(ListUsersRequest.newBuilder()
                    .setPage(PageRequest.newBuilder().setLimit(200).setCursor(cursor).build())
                    .build());
            users.addAll(response.getUsersList());
            cursor = response.getPage().getNextCursor();
            if (!response.getPage().getHasMore() || cursor.isBlank()) {
                break;
            }
        }
        return users;
    }
}
