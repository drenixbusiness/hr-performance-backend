package uz.drenix.identity.performance.report;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.drenix.identity.grpc.v1.ActivityStandard;
import uz.drenix.identity.grpc.v1.Entity;
import uz.drenix.identity.grpc.v1.ReportSubject;
import uz.drenix.identity.grpc.v1.TeamReportRow;
import uz.drenix.identity.performance.monday.DriverRecord;
import uz.drenix.identity.performance.metric.PerformanceCalculator;
import uz.drenix.identity.performance.metric.RecruitingStandard;
import uz.drenix.identity.performance.monday.MondayBoardClient;
import uz.drenix.identity.performance.store.DailyActivityEntity;
import uz.drenix.identity.performance.store.DailyActivityRepository;

/**
 * One row per person: what they did on the phone, and what came of it on the board.
 *
 * <p>The PDF and the written assessment are both built from this, so the two can never disagree
 * about somebody's figures — which they would within a week if each assembled its own.
 *
 * <p><b>Activity comes from the stored snapshots, not from RingCentral.</b> A month of call log is
 * minutes of throttled paging; the table answers in milliseconds. The consequence is that a range
 * before the snapshot job started running has no rows, and {@code recordedDays} says so rather
 * than the totals quietly being low.
 *
 * <p><b>Hiring is counted two ways.</b> Inside the period — drivers hired in the month being
 * reported, how many of those are still driving, how many left — because that is what a monthly
 * report is asking. And over all time beside it, because "42 hired this month" reads differently
 * next to "300 ever" than next to "45 ever".
 */
@Service
public class TeamReportService {

    private final DailyActivityRepository store;
    private final MondayBoardClient board;

    public TeamReportService(DailyActivityRepository store, MondayBoardClient board) {
        this.store = store;
        this.board = board;
    }

    public List<TeamReportRow> rows(List<ReportSubject> subjects, LocalDate from, LocalDate to,
                                    ActivityStandard standard) {
        if (subjects.isEmpty()) {
            return List.of();
        }

        Map<UUID, ReportSubject> byId = new LinkedHashMap<>();
        for (ReportSubject subject : subjects) {
            byId.put(UUID.fromString(subject.getId()), subject);
        }

        // Activity: one query for everybody, then bucketed. Per-person queries would be one round
        // trip each for a report that already has a person per row.
        Map<UUID, List<DailyActivityEntity>> activity = new HashMap<>();
        for (DailyActivityEntity row : store.range(byId.keySet(), from, to)) {
            activity.computeIfAbsent(row.getId().getUserId(), k -> new ArrayList<>()).add(row);
        }

        List<TeamReportRow> rows = new ArrayList<>(subjects.size());
        for (Map.Entry<UUID, ReportSubject> entry : byId.entrySet()) {
            rows.add(row(entry.getValue(), activity.getOrDefault(entry.getKey(), List.of()),
                    standard, from, to));
        }
        return rows;
    }

    private TeamReportRow row(ReportSubject subject, List<DailyActivityEntity> days,
                              ActivityStandard standard, LocalDate from, LocalDate to) {

        int calls = days.stream().mapToInt(DailyActivityEntity::getCalls).sum();
        long talk = days.stream().mapToLong(DailyActivityEntity::getTalkSeconds).sum();
        int sms = days.stream().mapToInt(DailyActivityEntity::getSmsSent).sum();

        // Judged at read time against whatever the standard is now, so history is re-judged rather
        // than frozen against a bar that has since moved.
        int metAll = (int) days.stream()
                .filter(d -> d.getCalls() >= standard.getCallsPerDay()
                             && d.getTalkSeconds() >= standard.getTalkSecondsPerDay()
                             && d.getSmsSent() >= standard.getSmsSentPerDay())
                .count();

        // The target is per recorded shift, not per calendar day. Somebody with four shifts stored
        // is measured against four shifts' worth, which is the only comparison that means anything
        // when the snapshot has gaps or somebody was on leave.
        int shifts = days.size();

        TeamReportRow.Builder row = TeamReportRow.newBuilder()
                .setAgentId(subject.getId())
                .setName(subject.getName())
                .setPosition(subject.getPosition())
                .setEntity(subject.getEntity())
                .setCalls(calls)
                .setTalkSeconds(talk)
                .setSmsSent(sms)
                .setRecordedDays(shifts)
                .setDaysAllTargetsMet(metAll)
                .setCallsTarget(standard.getCallsPerDay() * shifts)
                .setTalkSecondsTarget((long) standard.getTalkSecondsPerDay() * shifts)
                .setSmsSentTarget(standard.getSmsSentPerDay() * shifts);

        applyBoard(row, subject, from, to);
        return row.build();
    }

    /**
     * The hiring figures, matched by the name the board uses.
     *
     * <p>An unmatched person is flagged rather than filled with zeroes: "hired nobody" and "we
     * could not find you on the board" look identical on a page, and only one of them is somebody's
     * fault.
     */
    private void applyBoard(TeamReportRow.Builder row, ReportSubject subject,
                            LocalDate from, LocalDate to) {
        String mondayName = subject.getMondayName();
        if (mondayName == null || mondayName.isBlank()
                || subject.getEntity() == Entity.ENTITY_UNSPECIFIED) {
            row.setBoardMatched(false);
            return;
        }

        String entity = subject.getEntity().name().replace("ENTITY_", "");
        List<DriverRecord> drivers;
        try {
            drivers = board.snapshot(entity).drivers();
        } catch (RuntimeException e) {
            // The board being unreachable must not fail the whole report: the phone figures come
            // from our own database and are still good.
            row.setBoardMatched(false);
            return;
        }

        String wanted = mondayName.trim().toLowerCase(Locale.ROOT);
        int hiredInPeriod = 0;
        int terminatedInPeriod = 0;
        int stillActiveFromPeriod = 0;
        int hiredEver = 0;
        int activeNow = 0;
        int terminatedEver = 0;
        boolean seen = false;
        // Kept, because the tenure arithmetic below asks several more questions of exactly this
        // subset and walking the whole board again for each one would be four passes for nothing.
        List<DriverRecord> own = new ArrayList<>();

        for (DriverRecord driver : drivers) {
            if (driver.recruiter() == null
                    || !driver.recruiter().trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                continue;
            }
            seen = true;
            own.add(driver);

            if (driver.countableAsHire()) {
                hiredEver++;
                if (within(driver.hiredOn(), from, to)) {
                    hiredInPeriod++;
                    // Of the people hired this month, how many are still driving. The question a
                    // lead actually asks about a month is not "how many did you sign" but "how
                    // many of them stayed".
                    if (driver.currentlyActive()) {
                        stillActiveFromPeriod++;
                    }
                }
            }
            if (driver.currentlyActive()) {
                activeNow++;
            } else if (driver.terminatedOn() != null || driver.rawTerminatedOn() != null) {
                terminatedEver++;
                if (within(driver.terminatedOn(), from, to)) {
                    terminatedInPeriod++;
                }
            }
        }

        row.setBoardMatched(seen)
                .setHiredInPeriod(hiredInPeriod)
                .setTerminatedInPeriod(terminatedInPeriod)
                .setStillActiveFromPeriod(stillActiveFromPeriod)
                .setHiredToDate(hiredEver)
                .setActiveNow(activeNow)
                .setTerminatedToDate(terminatedEver);

        if (seen) {
            applyRecruitingStandard(row, subject, own, to);
        }
    }

    /**
     * The month somebody actually started, when a human has recorded it.
     *
     * <p>Parsed leniently: a date that will not parse is treated as absent rather than failing the
     * whole report. The row then says {@code INFERRED} and the reader can see that the recorded
     * date is not being used.
     */
    private static YearMonth recordedStart(ReportSubject subject) {
        String value = subject.getEmploymentStartDate();
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return YearMonth.from(LocalDate.parse(value.trim()));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * What the recruiting standard asked of this person in the reported month, and what they had.
     *
     * <p>Quarters run from the recruiter's own first hire rather than from January, so two people
     * who joined in different months are held to the same shape of target at the same point in
     * their own tenure. The count does not stop at four: somebody past their first year keeps the
     * fourth quarter's target, because the alternative is the longest-serving recruiters being the
     * only ones measured against nothing.
     *
     * <p>Both halves of the quarter are reported. A monthly target says whether this month was
     * good; the quarter-to-date figure and {@code quarterMonth} say whether the quarter is on
     * course, which is the question that actually decides anything in month one or two.
     *
     * @param own this recruiter's drivers, already filtered out of the board
     */
    private void applyRecruitingStandard(TeamReportRow.Builder row, ReportSubject subject,
                                         List<DriverRecord> own, LocalDate to) {
        YearMonth month = YearMonth.from(to);

        // A recorded start date beats an inferred one, always. The inference is the month of their
        // first hire on the board, and it can only ever be later than the truth: it cannot see the
        // months before somebody made their first hire, nor anything before the board existed. On
        // the BP board it read a lead of two years as fifteen months and a recruiter in his fourth
        // month as his fifth — the second of which moves him from month one of a quarter to month
        // two and nearly doubles the pace he is expected to have reached.
        YearMonth recorded = recordedStart(subject);
        YearMonth start = recorded != null ? recorded : PerformanceCalculator.firstHireMonth(own);
        row.setTenureSource(recorded != null ? "RECORDED" : "INFERRED");

        row.setActiveAtMonthEnd(PerformanceCalculator.activeAtEndOf(own, month, to));
        if (start == null || month.isBefore(start)) {
            // Nobody recorded a start date, every driver they have is undated, or the month
            // predates their start. None of those is a quarter, and inventing one would put
            // somebody on a target they cannot be held to.
            row.setTenureSource("");
            return;
        }

        long elapsed = start.until(month, ChronoUnit.MONTHS);
        int quarter = (int) (elapsed / 3) + 1;
        int quarterMonth = (int) (elapsed % 3) + 1;
        YearMonth quarterStart = month.minusMonths(quarterMonth - 1L);

        RecruitingStandard standard = RecruitingStandard.forTenureQuarter(quarter);
        row.setTenureStartMonth(start.toString())
                .setTenureMonths((int) elapsed + 1)
                .setRecruitingQuarter(quarter)
                .setQuarterMonth(quarterMonth)
                .setHiredInQuarter(PerformanceCalculator.hiresBetween(own, quarterStart, month))
                .setRequiredHiresInMonth(standard.hiresPerMonth())
                .setRequiredHiresInQuarter(standard.hiresPerQuarter())
                .setRequiredActive(standard.activeAtQuarterEnd());
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }
}
