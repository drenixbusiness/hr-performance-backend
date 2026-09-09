package uz.drenix.edge.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import uz.drenix.identity.grpc.v1.DataIssue;
import uz.drenix.identity.grpc.v1.HrPerformanceResponse;
import uz.drenix.identity.grpc.v1.MonthlyPoint;
import uz.drenix.identity.grpc.v1.QuarterResult;
import uz.drenix.identity.grpc.v1.RecruiterPerformance;

/** The performance chart, protobuf to plain records. */
public final class PerformanceViews {

    private PerformanceViews() {
    }

    @Schema(description = "One month of one recruiter's results.")
    public record MonthView(
            @Schema(example = "2026-03") String month,
            @Schema(description = "Drivers whose hire date falls in this month. Stays counted here "
                                  + "even if they have since left.", example = "6") int hired,
            @Schema(description = "Drivers whose leaving date falls in this month.", example = "2")
            int terminated,
            @Schema(description = "On the books at the end of the month: hired by then, not yet "
                                  + "gone. A point-in-time count, not hired minus terminated.",
                    example = "37") int active,
            @Schema(description = "Which of this recruiter's own quarters the month belongs to, "
                                  + "1–4. Zero outside their measured first year.", example = "3")
            int quarter,
            @Schema(description = "The month has not happened yet; all three counts are zero. Stop "
                                  + "drawing here.", example = "false") boolean future) {
    }

    @Schema(description = "A quarter's target and what actually happened.")
    public record QuarterView(
            @Schema(example = "2") int quarter,
            @Schema(description = "First month of this recruiter's quarter.", example = "2025-11")
            String firstMonth,
            @Schema(description = "Last month, inclusive.", example = "2026-01") String lastMonth,
            @Schema(example = "6") int requiredHiresPerMonth,
            @Schema(example = "18") int requiredHiresTotal,
            @Schema(example = "11") int requiredActive,
            @Schema(example = "15") int actualHiresTotal,
            @Schema(description = "Active drivers when the quarter ended, or right now if it is "
                                  + "still running.", example = "16") int actualActiveAtEnd,
            @Schema(example = "false") boolean hiresMet,
            @Schema(example = "true") boolean activeMet,
            @Schema(description = "False while the quarter is still running. Until it is true, a "
                                  + "missed target is not yet a miss.", example = "true")
            boolean complete) {
    }

    @Schema(description = "One recruiter's year.")
    public record RecruiterView(
            @Schema(description = "Which company's board this recruiter is on. The two boards are "
                                  + "separate, so this is what tells two people with the same "
                                  + "first name apart.", example = "BP",
                    allowableValues = {"JM", "BP"}) String entity,
            @Schema(description = "As spelled in the board's Source column.", example = "Alex")
            String recruiter,
            @Schema(description = "The matching account in this system, or null when no user has "
                                  + "that name.", example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338")
            String userId,
            @Schema(description = "Month of their earliest hire. Quarters are counted from here.",
                    example = "2025-08") String startedMonth,
            List<MonthView> months,
            List<QuarterView> quarters,
            @Schema(description = "Drivers in the active group right now.", example = "18")
            int activeNow,
            @Schema(example = "50") int requiredActiveAfterYear,
            @Schema(example = "false") boolean yearTargetMet,
            @Schema(description = "False until a full year has passed since their first hire.",
                    example = "true") boolean yearComplete) {
    }

    @Schema(description = "A row the chart could not fully account for.")
    public record IssueView(
            @Schema(description = "Which board the row is on.", example = "BP") String entity,
            @Schema(example = "LEWIS STEVEN") String itemName,
            @Schema(example = "Alex") String recruiter,
            @Schema(description = "MISSING_HIRE_DATE, MISSING_TERMINATION_DATE or "
                                  + "TERMINATION_BEFORE_HIRE.", example = "MISSING_TERMINATION_DATE")
            String kind,
            @Schema(example = "In the terminated group with no leaving date.") String problem) {
    }

    @Schema(description = "The chart. Read `dataIssues` before trusting a dip.")
    public record ChartView(
            @Schema(example = "2026") int year,
            List<RecruiterView> recruiters,
            @Schema(description = "Rows in the board that the numbers could not fully account for.")
            List<IssueView> dataIssues,
            @Schema(description = "When the board was last read. Results are cached for a few "
                                  + "minutes, so this can lag.", example = "2026-08-25T18:26:54Z")
            Instant generatedAt) {
    }

    public static ChartView of(HrPerformanceResponse response) {
        return new ChartView(
                response.getYear(),
                response.getRecruitersList().stream().map(PerformanceViews::of).toList(),
                response.getIssuesList().stream().map(PerformanceViews::of).toList(),
                Instant.ofEpochSecond(response.getGeneratedAtEpochSeconds()));
    }

    private static RecruiterView of(RecruiterPerformance recruiter) {
        return new RecruiterView(
                shortEntity(recruiter.getEntity()),
                recruiter.getRecruiter(),
                emptyToNull(recruiter.getUserId()),
                emptyToNull(recruiter.getStartedMonth()),
                recruiter.getMonthsList().stream().map(PerformanceViews::of).toList(),
                recruiter.getQuartersList().stream().map(PerformanceViews::of).toList(),
                recruiter.getActiveNow(),
                recruiter.getRequiredActiveAfterYear(),
                recruiter.getYearTargetMet(),
                recruiter.getYearComplete());
    }

    private static MonthView of(MonthlyPoint point) {
        return new MonthView(point.getMonth(), point.getHired(), point.getTerminated(),
                point.getActive(), point.getQuarter(), point.getFuture());
    }

    private static QuarterView of(QuarterResult result) {
        return new QuarterView(
                result.getQuarter(),
                result.getFirstMonth(),
                result.getLastMonth(),
                result.getRequiredHiresPerMonth(),
                result.getRequiredHiresTotal(),
                result.getRequiredActive(),
                result.getActualHiresTotal(),
                result.getActualActiveAtEnd(),
                result.getHiresMet(),
                result.getActiveMet(),
                result.getComplete());
    }

    private static IssueView of(DataIssue issue) {
        return new IssueView(shortEntity(issue.getEntity()), issue.getItemName(),
                emptyToNull(issue.getRecruiter()), issue.getKind(), issue.getProblem());
    }

    /** The bare company code, or null when a board somehow reported none. */
    private static String shortEntity(uz.drenix.identity.grpc.v1.Entity entity) {
        return entity == null || entity == uz.drenix.identity.grpc.v1.Entity.ENTITY_UNSPECIFIED
                ? null
                : entity.name().replace("ENTITY_", "");
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
