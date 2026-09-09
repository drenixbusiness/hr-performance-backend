package uz.drenix.identity.performance.metric;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import uz.drenix.identity.performance.monday.DriverRecord;

/**
 * Turns a board snapshot into one chart per recruiter.
 *
 * <p>Three things are worth knowing before reading the arithmetic.
 *
 * <p><b>A hire belongs to the month it happened, forever.</b> Someone hired in May who leaves in
 * August still counts as a May hire — the recruiter did that work and the month cannot be
 * retrospectively emptied. So the hired series is built from hire dates alone and never consults
 * the group a row currently sits in.
 *
 * <p><b>Active is a point-in-time count, not a running total.</b> The number for a month is how
 * many of that recruiter's drivers were on the books when the month ended: hired by then, not yet
 * gone. Accumulating hires minus terminations would give the same answer only if every row had
 * clean dates, and this board does not.
 *
 * <p><b>Quarters run from the recruiter's own start.</b> That start is their earliest hire, which
 * is the only evidence the board offers of when they began.
 */
@Component
public class PerformanceCalculator {

    public record MonthPoint(
            YearMonth month, int hired, int terminated, int active, int quarter, boolean future) {
    }

    public record QuarterOutcome(
            RecruitingStandard standard,
            YearMonth firstMonth,
            YearMonth lastMonth,
            int hires,
            int activeAtEnd,
            boolean complete) {

        public boolean hiresMet() {
            return hires >= standard.hiresPerQuarter();
        }

        public boolean activeMet() {
            return activeAtEnd >= standard.activeAtQuarterEnd();
        }
    }

    public record RecruiterChart(
            String recruiter,
            YearMonth startedMonth,
            List<MonthPoint> months,
            List<QuarterOutcome> quarters,
            int activeNow,
            boolean yearComplete,
            boolean yearTargetMet) {
    }

    /**
     * @param year  the calendar year to chart
     * @param today used to decide which quarters have finished; passed in rather than read from
     *              the clock so the result is reproducible
     */
    public List<RecruiterChart> calculate(List<DriverRecord> drivers, int year, LocalDate today) {
        Map<String, List<DriverRecord>> byRecruiter = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (DriverRecord driver : drivers) {
            if (driver.recruiter() != null && !driver.recruiter().isBlank()) {
                byRecruiter.computeIfAbsent(driver.recruiter().trim(), key -> new ArrayList<>()).add(driver);
            }
        }

        List<RecruiterChart> charts = new ArrayList<>(byRecruiter.size());
        byRecruiter.forEach((recruiter, own) -> charts.add(chartFor(recruiter, own, year, today)));
        charts.sort(Comparator.comparing(RecruiterChart::recruiter, String.CASE_INSENSITIVE_ORDER));
        return charts;
    }

    private RecruiterChart chartFor(String recruiter, List<DriverRecord> drivers, int year, LocalDate today) {
        YearMonth start = earliestHireMonth(drivers);

        YearMonth thisMonth = YearMonth.from(today);
        Map<YearMonth, MonthPoint> months = new LinkedHashMap<>();
        for (int month = 1; month <= 12; month++) {
            YearMonth ym = YearMonth.of(year, month);
            int quarter = start == null ? 0 : quarterOf(start, ym);

            if (ym.isAfter(thisMonth)) {
                // Nothing has happened in a month that has not arrived. Counting active here
                // would carry today's roster forward and draw a flat line into the future that
                // looks like fact.
                months.put(ym, new MonthPoint(ym, 0, 0, 0, quarter, true));
                continue;
            }
            months.put(ym, new MonthPoint(
                    ym,
                    countHiredIn(drivers, ym),
                    countTerminatedIn(drivers, ym),
                    activeAt(drivers, ym, today),
                    quarter,
                    false));
        }

        List<QuarterOutcome> quarters = start == null
                ? List.of()
                : quartersFor(drivers, start, today);

        int activeNow = (int) drivers.stream().filter(DriverRecord::currentlyActive).count();
        boolean yearComplete = start != null && !start.plusMonths(12).isAfter(YearMonth.from(today));

        return new RecruiterChart(
                recruiter,
                start,
                List.copyOf(months.values()),
                quarters,
                activeNow,
                yearComplete,
                yearComplete && activeNow >= RecruitingStandard.ACTIVE_AFTER_FIRST_YEAR);
    }

    private List<QuarterOutcome> quartersFor(List<DriverRecord> drivers, YearMonth start, LocalDate today) {
        List<QuarterOutcome> outcomes = new ArrayList<>(RecruitingStandard.all().size());
        YearMonth now = YearMonth.from(today);

        for (RecruitingStandard standard : RecruitingStandard.all()) {
            YearMonth first = start.plusMonths((long) (standard.quarter() - 1) * 3);
            YearMonth last = first.plusMonths(2);

            int hires = 0;
            for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
                hires += countHiredIn(drivers, month);
            }
            // A quarter still running is reported with the numbers so far but not judged: its
            // targets are for the whole three months.
            boolean complete = last.isBefore(now);
            int activeAtEnd = activeAt(drivers, complete ? last : now, today);

            outcomes.add(new QuarterOutcome(standard, first, last, hires, activeAtEnd, complete));
        }
        return outcomes;
    }

    /**
     * Which of the recruiter's own quarters a calendar month falls in, or 0 when the month sits
     * outside their measured first year — before they started, or after it ended.
     */
    private static int quarterOf(YearMonth start, YearMonth month) {
        if (month.isBefore(start)) {
            return 0;
        }
        long elapsed = start.until(month, java.time.temporal.ChronoUnit.MONTHS);
        int quarter = (int) (elapsed / 3) + 1;
        return quarter > RecruitingStandard.all().size() ? 0 : quarter;
    }

    private static YearMonth earliestHireMonth(List<DriverRecord> drivers) {
        return drivers.stream()
                .map(DriverRecord::hiredOn)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(YearMonth::from)
                .orElse(null);
    }

    /**
     * The month this recruiter's first driver started, or null when none of them has a hire date.
     *
     * <p>Exposed because the monthly report needs the same answer and must not get a different
     * one. Tenure decides which quarter's target somebody is held to, so two places computing it
     * from the same board and disagreeing would put a recruiter on two different standards in two
     * documents generated a second apart.
     *
     * @param drivers this recruiter's drivers only, already filtered
     */
    public static YearMonth firstHireMonth(List<DriverRecord> drivers) {
        return earliestHireMonth(drivers);
    }

    /**
     * Hires between two months, both ends included.
     *
     * @param drivers this recruiter's drivers only, already filtered
     */
    public static int hiresBetween(List<DriverRecord> drivers, YearMonth from, YearMonth to) {
        int hires = 0;
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            hires += countHiredIn(drivers, month);
        }
        return hires;
    }

    /**
     * Drivers on the books when {@code month} ended.
     *
     * @param drivers this recruiter's drivers only, already filtered
     * @param today   what "now" is, so the answer is reproducible
     */
    public static int activeAtEndOf(List<DriverRecord> drivers, YearMonth month, LocalDate today) {
        return activeAt(drivers, month, today);
    }

    private static int countHiredIn(List<DriverRecord> drivers, YearMonth month) {
        return (int) drivers.stream()
                .filter(DriverRecord::countableAsHire)
                .filter(d -> YearMonth.from(d.hiredOn()).equals(month))
                .count();
    }

    private static int countTerminatedIn(List<DriverRecord> drivers, YearMonth month) {
        return (int) drivers.stream()
                .filter(d -> d.terminatedOn() != null)
                // LocalDate.MIN marks "gone, date unknown" under the EXCLUDE_FROM_ACTIVE policy.
                // It keeps them out of the active line without inventing a month for the bar.
                .filter(d -> !d.terminatedOn().equals(LocalDate.MIN))
                .filter(d -> YearMonth.from(d.terminatedOn()).equals(month))
                .count();
    }

    /**
     * Active drivers at the end of {@code month}.
     *
     * <p>Drivers with no hire date cannot be placed on the timeline, so they are absent from every
     * past month — but the ones still sitting in the active group are demonstrably working now, and
     * leaving them out of the current month would report fewer people than the company has. They
     * are added to the present month only, which is the one month their status is evidence for.
     */
    private static int activeAt(List<DriverRecord> drivers, YearMonth month, LocalDate today) {
        LocalDate lastDay = month.atEndOfMonth();
        int dated = (int) drivers.stream().filter(d -> d.activeOn(lastDay)).count();
        if (!month.equals(YearMonth.from(today))) {
            return dated;
        }
        int undated = (int) drivers.stream()
                .filter(d -> d.hiredOn() == null && d.currentlyActive())
                .count();
        return dated + undated;
    }
}
