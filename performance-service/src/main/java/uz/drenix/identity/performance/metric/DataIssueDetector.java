package uz.drenix.identity.performance.metric;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import uz.drenix.identity.performance.monday.DriverRecord;

/**
 * Names the rows the chart could not fully account for.
 *
 * <p>Reported alongside the numbers rather than swallowed. A chart that silently drops rows is
 * worse than one that admits to them: the reader cannot tell a genuine dip from a missing date,
 * and the board never gets fixed because nobody is told what is wrong with it.
 *
 * <p>Works from {@link DriverRecord#rawTerminatedOn()}, not the corrected date, so it describes
 * the board as it is rather than as the chart had to pretend it was.
 */
@Component
public class DataIssueDetector {

    public enum Kind {
        /** No hire date, so the row cannot be placed in any month. */
        MISSING_HIRE_DATE,
        /** In the terminated group with no leaving date at all. */
        MISSING_TERMINATION_DATE,
        /** A leaving date before the hire date, describing nothing that can have happened. */
        TERMINATION_BEFORE_HIRE
    }

    public record Issue(String itemName, String recruiter, Kind kind, String problem) {
    }

    public List<Issue> detect(List<DriverRecord> drivers) {
        List<Issue> issues = new ArrayList<>();
        for (DriverRecord driver : drivers) {
            if (driver.hiredOn() == null) {
                issues.add(issue(driver, Kind.MISSING_HIRE_DATE,
                        driver.currentlyActive()
                                ? "No hire date. Counted in the active total for the current month "
                                  + "only, and in no month of the hired series."
                                : "No hire date, so this driver appears nowhere in the chart."));
            }
            if (!driver.currentlyActive() && driver.rawTerminatedOn() == null) {
                issues.add(issue(driver, Kind.MISSING_TERMINATION_DATE,
                        "In the terminated group with no leaving date."));
            }
            if (driver.rawTerminatedOn() != null && driver.hiredOn() != null
                    && driver.rawTerminatedOn().isBefore(driver.hiredOn())) {
                issues.add(issue(driver, Kind.TERMINATION_BEFORE_HIRE,
                        "Leaving date %s is before the hire date %s, so it was ignored."
                                .formatted(driver.rawTerminatedOn(), driver.hiredOn())));
            }
        }
        return issues;
    }

    private static Issue issue(DriverRecord driver, Kind kind, String problem) {
        String recruiter = driver.recruiter() == null ? "" : driver.recruiter();
        return new Issue(driver.itemName(), recruiter, kind, problem);
    }
}
