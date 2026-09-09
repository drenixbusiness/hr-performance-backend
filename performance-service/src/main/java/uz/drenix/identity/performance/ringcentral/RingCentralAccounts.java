package uz.drenix.identity.performance.ringcentral;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One {@link RingCentralClient} per company.
 *
 * <p>JM and BP hold genuinely separate RingCentral accounts. A number that exists in one does not
 * exist in the other, their call logs are disjoint, and each has its own ten-requests-a-minute
 * budget. Building a client per account rather than passing credentials per call is what keeps the
 * token cache and the rate limiter attached to the account they belong to — a shared limiter would
 * throttle JM for traffic BP generated.
 */
@Component
public class RingCentralAccounts {

    private static final Logger log = LoggerFactory.getLogger(RingCentralAccounts.class);

    private final Map<String, RingCentralClient> clients = new LinkedHashMap<>();
    private final String defaultEntity;

    public RingCentralAccounts(RingCentralProperties properties) {
        this.defaultEntity = normalise(properties.getDefaultEntity());

        properties.getAccounts().forEach((entity, account) -> {
            String key = normalise(entity);
            if (!account.isConfigured()) {
                // Not fatal. A deployment that has only brought one company online should serve
                // that one rather than refuse to start, and asking for the other says so plainly.
                log.warn("RingCentral account {} has no credentials; activity for it will fail", key);
            }
            clients.put(key, new RingCentralClient(properties, account, key));
        });

        if (clients.isEmpty()) {
            log.error("No RingCentral accounts are configured at all");
        } else {
            log.info("RingCentral accounts configured: {} (default {})",
                    clients.keySet(), defaultEntity);
        }
    }

    /**
     * The client for one company.
     *
     * <p>An unknown or blank entity falls back to the default rather than failing: somebody has to
     * own the numbers of an account whose role grants name no company, and reporting nothing for
     * them would look like a person who did no work.
     */
    public RingCentralClient forEntity(String entity) {
        String key = normalise(entity);
        RingCentralClient client = clients.get(key);
        if (client != null) {
            return client;
        }
        RingCentralClient fallback = clients.get(defaultEntity);
        if (fallback == null) {
            throw new RingCentralUnavailableException(
                    "No RingCentral account is configured for " + key
                    + ", and there is no default to fall back to", null);
        }
        return fallback;
    }

    /** The entity a client will actually be resolved to, for cache keys and for reporting. */
    public String resolve(String entity) {
        String key = normalise(entity);
        return clients.containsKey(key) ? key : defaultEntity;
    }

    public Set<String> configured() {
        return clients.keySet();
    }

    private static String normalise(String entity) {
        if (entity == null || entity.isBlank()) {
            return "";
        }
        // The gateway sends the proto enum name; configuration uses the bare code.
        return entity.trim().toUpperCase(Locale.ROOT).replace("ENTITY_", "");
    }
}
