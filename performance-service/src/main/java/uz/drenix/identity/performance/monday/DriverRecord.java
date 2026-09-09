package uz.drenix.identity.performance.monday;

import java.time.LocalDate;

/**
 * One driver, as the board describes them, reduced to what the chart needs.
 *
 * @param recruiter       the Source column, verbatim. Matching to an account happens later.
 * @param hiredOn         null when the board has no hire date, which makes the row uncountable.
 * @param terminatedOn    the leaving date the chart should use, after a missing or impossible one
 *                        has been replaced according to the configured policy.
 * @param rawTerminatedOn exactly what the termination column held, correction and all. Kept so the
 *                        data-quality report can describe the board as it really is rather than as
 *                        the chart had to pretend it was.
 * @param currentlyActive whether the row sits in the active group right now. Kept separately from
 *                        {@code terminatedOn} because the two disagree more often than one would
 *                        hope, and the disagreement is itself worth reporting.
 */
public record DriverRecord(
        String itemName,
        String recruiter,
        LocalDate hiredOn,
        LocalDate terminatedOn,
        LocalDate rawTerminatedOn,
        boolean currentlyActive) {

    public boolean countableAsHire() {
        return hiredOn != null;
    }

    /** True when the driver was on the books at the end of the given day. */
    public boolean activeOn(LocalDate day) {
        if (hiredOn == null || hiredOn.isAfter(day)) {
            return false;
        }
        return terminatedOn == null || terminatedOn.isAfter(day);
    }
}
