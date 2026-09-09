package uz.drenix.edge.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import uz.drenix.identity.grpc.v1.FeedbackResponse;
import uz.drenix.identity.grpc.v1.TeamReportResponse;
import uz.drenix.identity.grpc.v1.TeamReportRow;
import uz.drenix.identity.grpc.v1.User;

/** The team report and the written feedback, protobuf to plain records. */
public final class ReportViews {

    private ReportViews() {
    }

    @Schema(description = "One recruiter: what they did, and what came of it.")
    public record RowView(
            @Schema(example = "3d5a399e-2df0-4c06-9e74-098dae3243c9") String userId,
            @Schema(example = "t.isaac") String username,
            @Schema(example = "Isaac Taylor") String name,
            @Schema(example = "JM", allowableValues = {"JM", "BP"}) String entity,
            @Schema(description = "What they do, from their roles.", example = "HR Leader")
            String position,

            @Schema(description = "Shifts with a stored row. Fewer than the month means the "
                                  + "snapshot was not running then, not that nobody worked.",
                    example = "22") int recordedDays,
            @Schema(description = "Shifts where all three daily targets were met.", example = "9")
            int daysAllTargetsMet,

            @Schema(example = "2140") int calls,
            @Schema(description = "The daily target multiplied by `recordedDays`.", example = "2750")
            int callsTarget,
            @Schema(example = "118400") long talkSeconds,
            @Schema(example = "79200") long talkSecondsTarget,
            @Schema(example = "2980") int smsSent,
            @Schema(example = "3300") int smsSentTarget,

            @Schema(description = "Drivers hired **in the month reported**.", example = "4")
            int hiredInPeriod,
            @Schema(description = "Of those, still driving today.", example = "4")
            int stillActiveFromPeriod,
            @Schema(description = "Drivers terminated in the month reported.", example = "2")
            int terminatedInPeriod,

            @Schema(description = "Drivers credited to this recruiter over all time.",
                    example = "62") int hiredToDate,
            @Schema(description = "Of those, still driving.", example = "17") int activeNow,
            @Schema(description = "Of those, since terminated.", example = "45")
            int terminatedToDate,

            @Schema(description = "Which of the recruiter's own quarters the month falls in, "
                                  + "counted from their first ever hire. Keeps counting past 4 — "
                                  + "somebody past their first year stays on the quarter 4 "
                                  + "standard. **0** means the board matched nobody, so no "
                                  + "quarter could be worked out.",
                    example = "2") int recruitingQuarter,
            @Schema(description = "Where the month sits inside that quarter: 1, 2 or 3. A quarter "
                                  + "still running cannot be judged against its full target.",
                    example = "2") int quarterMonth,
            @Schema(description = "The month of their first ever hire, `YYYY-MM`. Stands in for an "
                                  + "employment start date, which this system does not hold. Empty "
                                  + "when the board matched nobody.", example = "2026-03")
            String tenureStartMonth,
            @Schema(description = "Whole months from `tenureStartMonth` to the reported month.",
                    example = "6") int tenureMonths,
            @Schema(description = "Where `tenureStartMonth` came from. **RECORDED** — somebody "
                                  + "entered an employment start date on the account. "
                                  + "**INFERRED** — taken from their first hire on the board, "
                                  + "which can only ever be later than the truth. Empty when no "
                                  + "quarter could be worked out at all. Show the difference: an "
                                  + "inferred tenure is a guess, and it decides which target the "
                                  + "person is measured against.",
                    example = "RECORDED", allowableValues = {"RECORDED", "INFERRED"})
            String tenureSource,
            @Schema(description = "Drivers the recruiting standard asked for in this month.",
                    example = "6") int requiredHiresInMonth,
            @Schema(description = "Hired across the whole current quarter so far.", example = "9")
            int hiredInQuarter,
            @Schema(description = "Drivers the standard asks for over the quarter's three months.",
                    example = "18") int requiredHiresInQuarter,
            @Schema(description = "Active drivers the standard asked for by the end of the "
                                  + "quarter.", example = "11") int requiredActive,
            @Schema(description = "Drivers on the books when the month ended. A point-in-time "
                                  + "count, not hires minus terminations.", example = "4")
            int activeAtMonthEnd,

            @Schema(description = "False when the board could not find this person. Every hiring "
                                  + "figure above is then a zero meaning **unknown**, not "
                                  + "\"hired nobody\" — show a dash.", example = "true")
            boolean boardMatched) {
    }


    @Schema(description = "The team report.")
    public record TeamReportView(
            @Schema(example = "2026-08-01") String from,
            @Schema(example = "2026-08-31") String to,
            @Schema(description = "The daily bar every row is measured against.")
            ActivityViews.StandardView standard,
            List<RowView> rows,
            @Schema(example = "2026-08-31T09:12:44Z") Instant generatedAt) {
    }

    @Schema(description = "Written feedback, and the numbers it was written from.")
    public record FeedbackView(
            @Schema(description = "Markdown. Show it as it is.",
                    example = "The team is ahead on calls and behind on messages…") String feedback,
            @Schema(example = "gpt-4o-mini") String model,
            @Schema(example = "3") int subjectsCovered,
            @Schema(description = "The same rows `GET /api/v1/reports/team` returns, so the "
                                  + "figures can be shown beside the words.") List<RowView> rows) {
    }

    public static TeamReportView of(TeamReportResponse response, Map<String, User> byId) {
        return new TeamReportView(
                response.getFromDate(),
                response.getToDate(),
                new ActivityViews.StandardView(
                        response.getStandard().getCallsPerDay(),
                        response.getStandard().getTalkSecondsPerDay(),
                        response.getStandard().getSmsSentPerDay(),
                        response.getStandard().getShiftMinutes()),
                response.getRowsList().stream().map(row -> of(row, byId)).toList(),
                Instant.ofEpochSecond(response.getGeneratedAtEpochSeconds()));
    }

    public static FeedbackView of(FeedbackResponse response, Map<String, User> byId) {
        return new FeedbackView(
                response.getFeedback(),
                response.getModel(),
                response.getSubjectsCovered(),
                response.getRowsList().stream().map(row -> of(row, byId)).toList());
    }

    private static RowView of(TeamReportRow row, Map<String, User> byId) {
        User user = byId.get(row.getAgentId());
        return new RowView(
                row.getAgentId(),
                user == null ? null : user.getUsername(),
                row.getName(),
                row.getEntity().name().replace("ENTITY_", ""),
                row.getPosition(),
                row.getRecordedDays(),
                row.getDaysAllTargetsMet(),
                row.getCalls(),
                row.getCallsTarget(),
                row.getTalkSeconds(),
                row.getTalkSecondsTarget(),
                row.getSmsSent(),
                row.getSmsSentTarget(),
                row.getHiredInPeriod(),
                row.getStillActiveFromPeriod(),
                row.getTerminatedInPeriod(),
                row.getHiredToDate(),
                row.getActiveNow(),
                row.getTerminatedToDate(),
                row.getRecruitingQuarter(),
                row.getQuarterMonth(),
                row.getTenureStartMonth(),
                row.getTenureMonths(),
                row.getTenureSource(),
                row.getRequiredHiresInMonth(),
                row.getHiredInQuarter(),
                row.getRequiredHiresInQuarter(),
                row.getRequiredActive(),
                row.getActiveAtMonthEnd(),
                row.getBoardMatched());
    }
}
