package uz.drenix.identity.performance.monday;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the driver board from monday.com.
 *
 * <p>The whole board is pulled and filtered here rather than queried per recruiter. It is a few
 * hundred rows, monday charges by complexity rather than by row, and one cached read serves every
 * recruiter's chart — asking once for everything is both cheaper and simpler than assembling many
 * narrow queries.
 *
 * <p>Reads are cached for {@link MondayProperties#getCacheTtl()}. On a failed refresh the previous
 * snapshot is served rather than an error: a chart that is ten minutes stale is worth more to the
 * person looking at it than a red box.
 */
@Component
public class MondayBoardClient {

    private static final Logger log = LoggerFactory.getLogger(MondayBoardClient.class);

    public record Snapshot(List<DriverRecord> drivers, Instant readAt) {
    }

    private final MondayProperties properties;
    private final RestClient restClient;
    /** One cached snapshot per company. The boards are read and refreshed independently. */
    private final Map<String, Snapshot> cached = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The builder comes from {@link RestClient#builder()} rather than an injected
     * {@code RestClient.Builder}. Spring Boot 4 moved that bean's auto-configuration into a
     * separate module, and this service has no other use for it — a static factory keeps the
     * dependency list honest about what is actually needed.
     */
    public MondayBoardClient(MondayProperties properties) {
        this.properties = properties;
        // No Authorization default header: the token can differ per company, so it is set per
        // request instead. Everything else about the connection is shared.
        this.restClient = RestClient.builder()
                .baseUrl(properties.getApiUrl())
                .defaultHeader("API-Version", properties.getApiVersion())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /** The companies this service has a board configured for. */
    public java.util.Set<String> entities() {
        return properties.getBoards().keySet();
    }

    public Snapshot snapshot(String entity) {
        Snapshot current = cached.get(entity);
        if (current != null && Duration.between(current.readAt(), Instant.now())
                .compareTo(properties.getCacheTtl()) < 0) {
            return current;
        }
        try {
            Snapshot fresh = new Snapshot(List.copyOf(fetchAll(entity)), Instant.now());
            cached.put(entity, fresh);
            return fresh;
        } catch (RuntimeException e) {
            if (current != null) {
                log.error("monday.com refresh failed for {}; serving the snapshot read at {}",
                        entity, current.readAt(), e);
                return current;
            }
            throw new MondayUnavailableException(
                    "Could not read the monday.com board for " + entity, e);
        }
    }

    private List<DriverRecord> fetchAll(String entity) {
        String token = properties.tokenFor(entity);
        if (token == null || token.isBlank()) {
            throw new MondayUnavailableException(
                    "No monday.com API token is configured for " + entity, null);
        }
        Long boardId = properties.getBoards().get(entity);
        if (boardId == null || boardId == 0L) {
            throw new MondayUnavailableException(
                    "No monday.com board is configured for " + entity, null);
        }

        MondayProperties.Columns columns = properties.columnsFor(entity);

        List<DriverRecord> drivers = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        // A hard stop: a cursor that never comes back null would otherwise loop forever against
        // somebody else's service.
        int maxPages = 100;

        do {
            Map<String, Object> page = requestPage(cursor, boardId, token, columns);
            cursor = (String) page.get("cursor");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
            if (items != null) {
                items.stream().map(item -> toDriver(item, columns))
                        .filter(java.util.Objects::nonNull).forEach(drivers::add);
            }
        } while (cursor != null && ++pages < maxPages);

        log.info("Read {} driver rows from the {} monday board {}", drivers.size(), entity, boardId);
        return drivers;
    }

    private Map<String, Object> requestPage(String cursor, long boardId, String token,
                                            MondayProperties.Columns ids) {
        String columns = "[\"%s\",\"%s\",\"%s\"]".formatted(
                ids.getSourceColumnId(),
                ids.getHireDateColumnId(),
                ids.getTerminationDateColumnId());

        String query = cursor == null
                ? """
                  { boards(ids: [%d]) { items_page(limit: %d) { cursor items { \
                  id name updated_at group { title } column_values(ids: %s) { id text } } } } }"""
                  .formatted(boardId, properties.getPageSize(), columns)
                : """
                  { next_items_page(limit: %d, cursor: "%s") { cursor items { \
                  id name updated_at group { title } column_values(ids: %s) { id text } } } }"""
                  .formatted(properties.getPageSize(), cursor, columns);

        Map<String, Object> body = restClient.post()
                .header("Authorization", token)
                .body(Map.of("query", query))
                .retrieve()
                .body(Map.class);

        if (body == null) {
            throw new MondayUnavailableException("Empty response from monday.com", null);
        }
        if (body.get("errors") != null) {
            // The message is monday's, about our own query — safe to log, never returned to a caller.
            throw new MondayUnavailableException("monday.com rejected the query: " + body.get("errors"), null);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        if (cursor != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) data.get("next_items_page");
            return next;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> boards = (List<Map<String, Object>>) data.get("boards");
        if (boards == null || boards.isEmpty()) {
            throw new MondayUnavailableException(
                    "Board " + boardId + " is not visible to this token", null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) boards.get(0).get("items_page");
        return page;
    }

    /** @return null for rows in groups the chart does not describe. */
    private DriverRecord toDriver(Map<String, Object> item, MondayProperties.Columns ids) {
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) item.get("group");
        String groupTitle = group == null ? "" : String.valueOf(group.get("title"));

        boolean active = properties.getActiveGroup().equalsIgnoreCase(groupTitle);
        boolean terminated = properties.getTerminatedGroup().equalsIgnoreCase(groupTitle);
        if (!active && !terminated) {
            // Rejected, Notifications, priority buckets: candidates and noise, not drivers.
            return null;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) item.get("column_values");
        String recruiter = text(values, ids.getSourceColumnId());
        LocalDate hiredOn = date(text(values, ids.getHireDateColumnId()));
        LocalDate rawTerminatedOn = date(text(values, ids.getTerminationDateColumnId()));
        LocalDate terminatedOn = rawTerminatedOn;

        if (terminated) {
            // A leaving date before the hire date describes nothing that can have happened, so it
            // is discarded rather than trusted; the fallback below then applies.
            if (terminatedOn != null && hiredOn != null && terminatedOn.isBefore(hiredOn)) {
                terminatedOn = null;
            }
            if (terminatedOn == null) {
                terminatedOn = fallbackTermination(item, hiredOn);
            }
        } else {
            // Still on the board as active; whatever is in the termination column is stale.
            terminatedOn = null;
        }

        return new DriverRecord(String.valueOf(item.get("name")), recruiter,
                hiredOn, terminatedOn, rawTerminatedOn, active);
    }

    /** Applies {@link MondayProperties.UnknownTerminationPolicy} to a row with no usable date. */
    private LocalDate fallbackTermination(Map<String, Object> item, LocalDate hiredOn) {
        return switch (properties.getUnknownTermination()) {
            case ASSUME_STILL_ACTIVE -> null;
            // A date before every possible month keeps the driver out of the active line without
            // ever landing in a terminated bar the chart would show.
            case EXCLUDE_FROM_ACTIVE -> hiredOn == null ? LocalDate.MIN : hiredOn;
            case USE_UPDATED_AT -> {
                LocalDate edited = instantDate(String.valueOf(item.get("updated_at")));
                yield edited == null || (hiredOn != null && edited.isBefore(hiredOn)) ? null : edited;
            }
        };
    }

    private static String text(List<Map<String, Object>> values, String columnId) {
        if (values == null) {
            return null;
        }
        for (Map<String, Object> value : values) {
            if (columnId.equals(value.get("id"))) {
                Object text = value.get("text");
                String s = text == null ? null : String.valueOf(text).trim();
                return s == null || s.isEmpty() ? null : s;
            }
        }
        return null;
    }

    /** monday renders dates as {@code yyyy-MM-dd}, sometimes with a time appended. */
    private static LocalDate date(String raw) {
        if (raw == null || raw.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate instantDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException e) {
            return date(raw);
        }
    }
}
