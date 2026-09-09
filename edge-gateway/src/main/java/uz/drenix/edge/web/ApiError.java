package uz.drenix.edge.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The single error shape the API returns. Codes are stable and machine-readable; messages are
 * written for a person and never contain internals.
 */
@Schema(description = "The single error shape this API returns.")
public record ApiError(
        @Schema(description = "Stable, machine-readable error code.", example = "invalid_request")
        String code,
        @Schema(description = "A message written for a person. Never contains internals.",
                example = "username: must not be blank")
        String message,
        @Schema(description = "When the failure happened (UTC).", example = "2026-08-25T15:00:03Z")
        Instant timestamp) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now());
    }
}
