package uz.drenix.identity.user.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only audit trail.
 *
 * <p>Writes run in {@link Propagation#REQUIRES_NEW} so a denied or failed operation still leaves a
 * record after its own transaction rolls back — the failures are exactly the rows worth keeping.
 * The table also carries rules that turn UPDATE and DELETE into no-ops, so reaching the
 * application role is not enough to rewrite history.
 */
@Service
public class AuditService {

    public enum Outcome { SUCCESS, DENIED, FAILURE }

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            UUID actorId,
            String actorUsername,
            String action,
            String targetType,
            String targetId,
            Outcome outcome,
            String clientIp,
            String userAgent,
            String correlationId,
            Map<String, Object> detail) {

        try {
            entityManager.createNativeQuery("""
                    INSERT INTO audit_log
                      (actor_id, actor_username, action, target_type, target_id,
                       outcome, client_ip, user_agent, correlation_id, detail)
                    VALUES (?1, ?2, ?3, ?4, ?5, ?6, CAST(?7 AS INET), ?8, ?9, CAST(?10 AS JSONB))
                    """)
                    .setParameter(1, actorId)
                    .setParameter(2, actorUsername)
                    .setParameter(3, action)
                    .setParameter(4, targetType)
                    .setParameter(5, targetId)
                    .setParameter(6, outcome.name())
                    .setParameter(7, blankToNull(clientIp))
                    .setParameter(8, userAgent)
                    .setParameter(9, correlationId)
                    .setParameter(10, toJson(detail))
                    .executeUpdate();
        } catch (Exception e) {
            // An audit write must never take down the request it describes, but a silent drop is
            // worse than noise: log loudly so the gap shows up in monitoring.
            log.error("AUDIT WRITE FAILED action={} target={}/{} correlation={}",
                    action, targetType, targetId, correlationId, e);
        }
    }

    /**
     * Hand-rolled instead of Jackson.
     *
     * <p>The detail map only ever holds strings, numbers, booleans and collections of those, and
     * Spring Boot 4 moved to Jackson 3 under a different package. Twenty lines here beats a
     * compile break the next time the JSON library reorganises.
     */
    private static String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return "{}";
        }
        StringJoiner json = new StringJoiner(",", "{", "}");
        detail.forEach((key, value) -> json.add(quote(key) + ":" + renderValue(value)));
        return json.toString();
    }

    private static String renderValue(Object value) {
        return switch (value) {
            case null -> "null";
            case Number number -> number.toString();
            case Boolean bool -> bool.toString();
            case Iterable<?> items -> {
                StringJoiner array = new StringJoiner(",", "[", "]");
                items.forEach(item -> array.add(renderValue(item)));
                yield array.toString();
            }
            default -> quote(value.toString());
        };
    }

    private static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
