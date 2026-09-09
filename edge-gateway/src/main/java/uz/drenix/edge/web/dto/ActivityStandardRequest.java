package uz.drenix.edge.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * A new activity standard.
 *
 * <p>All four numbers, every time. They are read together and mean nothing apart: raising the call
 * target without saying what a shift is leaves a standard nobody can evaluate a part-day against.
 * Send the current values back for the ones you are not changing — {@code GET} returns them in the
 * shape this expects.
 */
@Schema(description = "The daily call, talk and SMS targets. A full replacement, not a patch.")
public record ActivityStandardRequest(

        @Schema(description = "Call attempts expected in one shift, inbound or outbound, connected "
                              + "or not.", example = "125", requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(0) @Max(100_000) int callsPerDay,

        @Schema(description = "Seconds on the phone expected in one shift. 3600 is one hour.",
                example = "3600", requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(0) @Max(86_400) int talkSecondsPerDay,

        @Schema(description = "Outbound messages expected in one shift. Received messages are never "
                              + "counted.", example = "150", requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(0) @Max(100_000) int smsSentPerDay,

        @Schema(description = "How long a full shift is, in minutes. 540 is nine hours, which is "
                              + "18:00 to 03:00. Asking for part of a day scales the three targets "
                              + "by the fraction of this that the window covers.",
                example = "540", requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(1) @Max(1440) int shiftMinutes) {
}
