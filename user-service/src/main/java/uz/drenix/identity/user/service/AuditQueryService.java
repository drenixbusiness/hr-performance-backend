package uz.drenix.identity.user.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the audit trail.
 *
 * <p>Kept apart from {@link AuditService}, which only ever appends. Two classes rather than one
 * so that nothing on the write path can grow a read, and nothing here can grow a write: the
 * table's whole value rests on being append-only, and the database enforces that with rules
 * against UPDATE and DELETE.
 *
 * <p>Paging is by descending id. Ids are handed out in insertion order and rows are never removed,
 * so a page boundary cannot drift the way an OFFSET does while new entries arrive.
 */
@Service
public class AuditQueryService {

    /** Ceiling on one page, whatever the caller asks for. */
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    public record Entry(
            long id,
            Instant occurredAt,
            String actorId,
            String actorUsername,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String clientIp,
            String userAgent,
            String correlationId,
            String detailJson) {
    }

    public record Page(List<Entry> entries, String nextCursor, boolean hasMore) {
    }

    public record Filter(
            String actorUsername,
            String action,
            String outcome,
            String targetId,
            Instant occurredFrom,
            Instant occurredTo) {
    }

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Page list(Filter filter, String cursor, int limit) {
        int pageSize = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Long before = parseCursor(cursor);

        StringBuilder sql = new StringBuilder("""
                SELECT id, occurred_at, actor_id, actor_username, action, target_type, target_id,
                       outcome, host(client_ip), user_agent, correlation_id, detail::text
                FROM audit_log
                WHERE 1 = 1
                """);
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();

        if (before != null) {
            sql.append(" AND id < :before");
            parameters.put("before", before);
        }
        if (isSet(filter.actorUsername())) {
            sql.append(" AND actor_username = :actorUsername");
            parameters.put("actorUsername", filter.actorUsername());
        }
        if (isSet(filter.action())) {
            sql.append(" AND action = :action");
            parameters.put("action", filter.action());
        }
        if (isSet(filter.outcome())) {
            sql.append(" AND outcome = :outcome");
            parameters.put("outcome", filter.outcome());
        }
        if (isSet(filter.targetId())) {
            sql.append(" AND target_id = :targetId");
            parameters.put("targetId", filter.targetId());
        }
        if (filter.occurredFrom() != null) {
            sql.append(" AND occurred_at >= :occurredFrom");
            parameters.put("occurredFrom", filter.occurredFrom());
        }
        if (filter.occurredTo() != null) {
            sql.append(" AND occurred_at < :occurredTo");
            parameters.put("occurredTo", filter.occurredTo());
        }

        // One extra row is fetched purely to answer "is there a next page" without a second query.
        sql.append(" ORDER BY id DESC LIMIT :limit");
        parameters.put("limit", pageSize + 1);

        Query query = entityManager.createNativeQuery(sql.toString());
        parameters.forEach(query::setParameter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        boolean hasMore = rows.size() > pageSize;
        List<Entry> entries = new ArrayList<>(Math.min(rows.size(), pageSize));
        for (int i = 0; i < Math.min(rows.size(), pageSize); i++) {
            entries.add(toEntry(rows.get(i)));
        }

        String nextCursor = hasMore && !entries.isEmpty()
                ? Long.toString(entries.get(entries.size() - 1).id())
                : null;
        return new Page(entries, nextCursor, hasMore);
    }

    private static Entry toEntry(Object[] row) {
        return new Entry(
                ((Number) row[0]).longValue(),
                toInstant(row[1]),
                text(row[2]),
                text(row[3]),
                text(row[4]),
                text(row[5]),
                text(row[6]),
                text(row[7]),
                text(row[8]),
                text(row[9]),
                text(row[10]),
                text(row[11]));
    }

    /**
     * A cursor that is not a number is treated as no cursor at all rather than as an error: it is
     * an opaque token to the caller, and the worst a mangled one can do is start from the top.
     */
    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * A {@code timestamptz} comes back from a native query as whatever the JDBC driver and the
     * Hibernate version between them decide on — {@link Instant} here, {@link java.sql.Timestamp}
     * on other combinations. Pinning either one turns a driver upgrade into a ClassCastException
     * at runtime, so both are accepted.
     */
    private static Instant toInstant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            case java.time.OffsetDateTime offset -> offset.toInstant();
            default -> throw new IllegalStateException(
                    "Unexpected timestamp type from audit_log: " + value.getClass());
        };
    }
}
