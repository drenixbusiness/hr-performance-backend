package uz.drenix.identity.performance.metric;

import java.util.List;

/**
 * What a recruiter is expected to deliver in each of their first four quarters.
 *
 * <p>A quarter is three months counted from the recruiter's own start, not from January. Two
 * people who joined in different months are therefore measured against the same shape of target
 * at the same point in their own tenure, which is the only comparison that means anything.
 *
 * <p>The hire targets are per quarter, not cumulative: 12 + 18 + 24 + 30 over the four quarters.
 * The active targets are the opposite — a level to stand at when the quarter ends, and each one is
 * higher than the last, so keeping drivers matters as much as finding them. Hiring thirty people
 * in the fourth quarter and holding seventeen still misses.
 */
public record RecruitingStandard(
        int quarter,
        int hiresPerMonth,
        int hiresPerQuarter,
        int activeAtQuarterEnd) {

    /** Active drivers expected once a full year has passed. */
    public static final int ACTIVE_AFTER_FIRST_YEAR = 50;

    private static final List<RecruitingStandard> QUARTERS = List.of(
            new RecruitingStandard(1, 4, 12, 7),
            new RecruitingStandard(2, 6, 18, 11),
            new RecruitingStandard(3, 8, 24, 14),
            new RecruitingStandard(4, 10, 30, 18));

    public static List<RecruitingStandard> all() {
        return QUARTERS;
    }

    /** @throws IllegalArgumentException for a quarter outside the measured first year. */
    public static RecruitingStandard forQuarter(int quarter) {
        if (quarter < 1 || quarter > QUARTERS.size()) {
            throw new IllegalArgumentException("No standard is defined for quarter " + quarter);
        }
        return QUARTERS.get(quarter - 1);
    }

    /**
     * The standard for any quarter of tenure, however long somebody has been here.
     *
     * <p>The four quarters above are a ramp, not a career: they exist so that a recruiter in their
     * first month is not judged against somebody in their tenth. Once the ramp is finished it stops
     * being a ramp and becomes the job — the fourth quarter's targets hold for the fifth quarter,
     * the eighth and the twentieth.
     *
     * <p>The alternative, which this replaces, was to report no target at all past twelve months.
     * That left the longest-serving recruiters as the only people in the company measured against
     * nothing, which is exactly backwards.
     *
     * @throws IllegalArgumentException when {@code quarter} is below one
     */
    public static RecruitingStandard forTenureQuarter(int quarter) {
        if (quarter < 1) {
            throw new IllegalArgumentException("No standard is defined for quarter " + quarter);
        }
        return QUARTERS.get(Math.min(quarter, QUARTERS.size()) - 1);
    }
}
