package uz.drenix.edge.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Is this process alive?
 *
 * <p>Deliberately separate from Spring Boot's actuator, which listens on its own management port
 * and is not published. That arrangement is right — an operator's endpoint should not share a
 * socket with the public API — but it left the public port with no health check at all, and the
 * security chain permitting {@code /actuator/health} on it was permitting a path that answered
 * 404. A load balancer, a container healthcheck and a deployment script all need something they
 * can reach on the port they already talk to.
 *
 * <p><b>Liveness only.</b> It answers from the process itself and consults nothing: no database,
 * no Redis, no downstream service. A health check that fails because Postgres is briefly busy
 * tells an orchestrator to restart a gateway that was working perfectly, which turns a small
 * problem into an outage. Readiness — "can this instance serve traffic" — is the actuator's
 * business on the management port, where the detail is safe to expose.
 *
 * <p><b>It says nothing else.</b> No version, no uptime, no dependency status, no hostname. This
 * is the one endpoint reachable without a token, so everything it reveals is revealed to the
 * internet.
 */
@RestController
@Tag(name = "Health")
public class HealthController {

    private static final Map<String, String> UP = Map.of("status", "UP");

    @Operation(
            summary = "Liveness",
            description = """
                    Answers `{"status":"UP"}` when the process is running. Public: no token needed.

                    It checks nothing but itself. A failing database does not make this endpoint \
                    fail, on purpose — restarting a healthy gateway because Postgres was busy \
                    turns a slow minute into an outage. For dependency detail use the actuator on \
                    the management port, which is not published.""")
    @ApiResponse(responseCode = "200", description = "The process is alive.",
            content = @io.swagger.v3.oas.annotations.media.Content(
                    schema = @Schema(example = "{\"status\":\"UP\"}")))
    @SecurityRequirements
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return UP;
    }
}
