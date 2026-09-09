package uz.drenix.edge.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import uz.drenix.identity.grpc.v1.ActivityHistoryResponse;
import uz.drenix.identity.grpc.v1.ActivityStandard;
import uz.drenix.identity.grpc.v1.ActivityStandardSettings;
import uz.drenix.identity.grpc.v1.AgentActivity;
import uz.drenix.identity.grpc.v1.AgentHistory;
import uz.drenix.identity.grpc.v1.DailyActivity;
import uz.drenix.identity.grpc.v1.HourPoint;
import uz.drenix.identity.grpc.v1.HrActivityResponse;
import uz.drenix.identity.grpc.v1.User;

/**
 * Call and SMS activity, protobuf to plain records.
 *
 * <p>performance-service knows only phone numbers, so the account behind each row is attached here
 * — the gateway is the only place that holds both.
 */
public final class ActivityViews {

    private ActivityViews() {
    }

    @Schema(description = "The bar a person is measured against for one whole shift.")
    public record StandardView(
            @Schema(description = "Call attempts per shift, inbound or outbound, connected or not.",
                    example = "125") int callsPerDay,
            @Schema(description = "Total seconds on calls per shift.", example = "3600")
            int talkSecondsPerDay,
            @Schema(description = "Outbound messages per shift.", example = "150") int smsSentPerDay,
            @Schema(description = "How long a full shift is, in minutes. Targets are scaled by this "
                                  + "when you ask about part of a day.", example = "540")
            int shiftMinutes) {
    }

    @Schema(description = "The editable standard, with who last changed it.")
    public record StandardSettingsView(
            @Schema(example = "125") int callsPerDay,
            @Schema(example = "3600") int talkSecondsPerDay,
            @Schema(example = "150") int smsSentPerDay,
            @Schema(example = "540") int shiftMinutes,
            @Schema(description = "Null until somebody changes it.",
                    example = "2026-08-28T09:12:44Z") Instant updatedAt,
            @Schema(description = "Null until somebody changes it.", example = "l.jamshid")
            String updatedBy) {
    }

    @Schema(description = "One clock hour inside the window.")
    public record HourView(
            @Schema(description = "Local to the report's `zone`, no offset.",
                    example = "2026-08-27T18:00") String hour,
            @Schema(example = "17") int calls,
            @Schema(example = "612") int talkSeconds,
            @Schema(example = "21") int smsSent) {
    }

    @Schema(description = "One person's shift.")
    public record DayView(
            @Schema(description = "The day the shift STARTED. A 18:00-03:00 shift on the night of "
                                  + "the 27th is reported here in full under 2026-08-27.",
                    example = "2026-08-27") String date,
            @Schema(description = "Every call attempt in the window.", example = "82") int calls,
            @Schema(description = "Total seconds on calls.", example = "4980") int talkSeconds,
            @Schema(description = "Outbound messages only.", example = "143") int smsSent,
            @Schema(description = "Measured against `windowStandard`, not the full-shift standard.",
                    example = "false") boolean callsMet,
            @Schema(example = "true") boolean talkMet,
            @Schema(example = "false") boolean smsMet,
            @Schema(description = "Hour-by-hour breakdown, empty unless you asked for a clock "
                                  + "window with `fromTime`/`toTime`.") List<HourView> hours) {
    }

    @Schema(description = "One person over the whole range.")
    public record AgentView(
            @Schema(description = "The account, when the number belongs to one.",
                    example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338") String userId,
            @Schema(example = "t.isaac") String username,
            @Schema(description = "The name on the account.", example = "Isaac Taylor") String fullName,
            @Schema(description = "The name RingCentral has on the extension. Usually the same, but "
                                  + "worth showing when the two disagree.",
                    example = "Isaac Taylor") String ringCentralName,
            @Schema(description = "The first of the numbers counted into this row.",
                    example = "+13313291144") String ringCentralPhone,
            @Schema(description = "Every number counted into this row. A lead who works two "
                                  + "extensions has both here and one set of totals.")
            List<String> ringCentralPhones,
            @Schema(description = "Numbers that are not on the RingCentral account at all — one "
                                  + "this person does not work, or one typed wrong. It does not "
                                  + "mean they made no calls: a number that only ever sent "
                                  + "messages still resolves. The rest of the row is real; these "
                                  + "contribute nothing.")
            List<String> unresolvedPhones,
            List<DayView> days,
            @Schema(example = "1640") int totalCalls,
            @Schema(example = "99600") long totalTalkSeconds,
            @Schema(example = "2860") int totalSmsSent,
            @Schema(description = "Shifts on which anything happened. Divide totals by this rather "
                                  + "than by the length of the range, or leave counts as failure.",
                    example = "20") int activeDays,
            @Schema(description = "True when NONE of the numbers resolved, so nothing could be "
                                  + "read at all. Not the same as a quiet week — treat the zeroes "
                                  + "as unknown, not as poor performance. When only some numbers "
                                  + "are missing this stays false and `unresolvedPhones` names "
                                  + "them.",
                    example = "false") boolean extensionUnresolved) {
    }

    @Schema(description = "One person over a stored range. No hour breakdown and no unresolved "
                          + "numbers: the snapshot recorded totals per shift, not the call log.")
    public record HistoryAgentView(
            @Schema(example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338") String userId,
            @Schema(example = "t.isaac") String username,
            @Schema(example = "Isaac Taylor") String fullName,
            @Schema(description = "The company whose RingCentral account these numbers live in.",
                    example = "JM", allowableValues = {"JM", "BP"}) String entity,
            List<DayView> days,
            @Schema(example = "2140") int totalCalls,
            @Schema(example = "118400") long totalTalkSeconds,
            @Schema(example = "2980") int totalSmsSent,
            @Schema(description = "Shifts with a stored row. **Fewer than the range means the "
                                  + "snapshot was not running then, not that nobody worked.** "
                                  + "Divide totals by this, never by the length of the range.",
                    example = "22") int recordedDays,
            @Schema(description = "Shifts where all three targets were met.", example = "9")
            int daysAllTargetsMet) {
    }

    @Schema(description = "Stored activity, read from this system's own database.")
    public record HistoryView(
            @Schema(example = "2026-08-01") String from,
            @Schema(example = "2026-08-31") String to,
            @Schema(example = "UTC") String zone,
            @Schema(description = "The bar every stored shift was judged against — applied now, "
                                  + "at read time, so changing the standard re-judges history "
                                  + "rather than rewriting it.") StandardView standard,
            List<HistoryAgentView> agents) {
    }

    @Schema(description = "Call and SMS activity for everyone the caller may see.")
    public record ActivityView(
            @Schema(example = "2026-08-27") String from,
            @Schema(example = "2026-08-28") String to,
            @Schema(description = "Start of the clock window, null when whole days were asked for.",
                    example = "18:00") String fromTime,
            @Schema(description = "End of the clock window, exclusive.", example = "03:00")
            String toTime,
            @Schema(description = "The zone every date and hour here is expressed in.",
                    example = "UTC") String zone,
            @Schema(description = "Length of the window actually counted, in minutes.",
                    example = "540") int windowMinutes,
            @Schema(description = "The full-shift standard, for reference.") StandardView standard,
            @Schema(description = "The standard scaled to `windowMinutes`. This is what `callsMet`, "
                                  + "`talkMet` and `smsMet` are judged against. Equal to `standard` "
                                  + "when the window is a full shift or longer.")
            StandardView windowStandard,
            List<AgentView> agents,
            @Schema(description = "When RingCentral was last read. Results are cached for a few "
                                  + "minutes.", example = "2026-08-28T09:30:00Z") Instant generatedAt) {
    }

    /** The accounts this answer is about, keyed by the selector id that was sent. */
    public static ActivityView of(HrActivityResponse response, Map<String, User> byId) {
        return new ActivityView(
                response.getFromDate(),
                response.getToDate(),
                emptyToNull(response.getFromTime()),
                emptyToNull(response.getToTime()),
                response.getZone(),
                response.getWindowMinutes(),
                of(response.getStandard()),
                of(response.getWindowStandard()),
                response.getAgentsList().stream()
                        .map(agent -> of(agent, byId.get(agent.getAgentId())))
                        .toList(),
                Instant.ofEpochSecond(response.getGeneratedAtEpochSeconds()));
    }

    public static HistoryView of(ActivityHistoryResponse response, Map<String, User> byId) {
        return new HistoryView(
                response.getFromDate(),
                response.getToDate(),
                response.getZone(),
                of(response.getStandard()),
                response.getAgentsList().stream()
                        .map(agent -> of(agent, byId.get(agent.getAgentId())))
                        .toList());
    }

    private static HistoryAgentView of(AgentHistory agent, User user) {
        return new HistoryAgentView(
                agent.getAgentId(),
                user == null ? null : user.getUsername(),
                user == null ? null : user.getFullName(),
                user == null ? null : shortEntity(user),
                agent.getDaysList().stream().map(ActivityViews::of).toList(),
                agent.getTotalCalls(),
                agent.getTotalTalkSeconds(),
                agent.getTotalSmsSent(),
                agent.getRecordedDays(),
                agent.getDaysAllTargetsMet());
    }

    private static String shortEntity(User user) {
        uz.drenix.identity.grpc.v1.Entity entity = uz.drenix.edge.web.TeamDirectory.companyOf(user);
        return entity == uz.drenix.identity.grpc.v1.Entity.ENTITY_UNSPECIFIED
                ? null
                : entity.name().replace("ENTITY_", "");
    }

    public static StandardSettingsView of(ActivityStandardSettings settings) {
        return new StandardSettingsView(
                settings.getCallsPerDay(),
                settings.getTalkSecondsPerDay(),
                settings.getSmsSentPerDay(),
                settings.getShiftMinutes(),
                settings.getUpdatedAt().isEmpty() ? null : Instant.parse(settings.getUpdatedAt()),
                emptyToNull(settings.getUpdatedBy()));
    }

    private static StandardView of(ActivityStandard standard) {
        return new StandardView(standard.getCallsPerDay(), standard.getTalkSecondsPerDay(),
                standard.getSmsSentPerDay(), standard.getShiftMinutes());
    }

    private static AgentView of(AgentActivity agent, User user) {
        return new AgentView(
                user == null ? null : user.getId(),
                user == null ? null : user.getUsername(),
                user == null ? null : user.getFullName(),
                emptyToNull(agent.getDisplayName()),
                emptyToNull(agent.getRingCentralPhone()),
                agent.getRingCentralPhonesList(),
                agent.getUnresolvedPhonesList(),
                agent.getDaysList().stream().map(ActivityViews::of).toList(),
                agent.getTotalCalls(),
                agent.getTotalTalkSeconds(),
                agent.getTotalSmsSent(),
                agent.getActiveDays(),
                agent.getExtensionUnresolved());
    }

    private static DayView of(DailyActivity day) {
        return new DayView(day.getDate(), day.getCalls(), day.getTalkSeconds(), day.getSmsSent(),
                day.getCallsMet(), day.getTalkMet(), day.getSmsMet(),
                day.getHoursList().stream().map(ActivityViews::of).toList());
    }

    private static HourView of(HourPoint hour) {
        return new HourView(hour.getHour(), hour.getCalls(), hour.getTalkSeconds(),
                hour.getSmsSent());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
