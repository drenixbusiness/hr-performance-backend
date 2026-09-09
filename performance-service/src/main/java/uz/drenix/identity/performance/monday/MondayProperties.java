package uz.drenix.identity.performance.monday;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about the board that could differ between deployments.
 *
 * <p>Column ids rather than titles. Renaming a column in monday leaves its id alone, so titles
 * would break the moment somebody tidied the board; the ids never change.
 */
@ConfigurationProperties(prefix = "drenix.monday")
public class MondayProperties {

    /** How a termination with no usable date is treated. */
    public enum UnknownTerminationPolicy {
        /**
         * Use the item's {@code updated_at} as the leaving date. Chosen because it is the only
         * date monday still holds for these rows — but it is the time of the <em>last edit</em>,
         * so a bulk tidy-up of the board stamps every affected row with the same month.
         */
        USE_UPDATED_AT,
        /** Count the hire, and treat the driver as still active. Overstates the active line. */
        ASSUME_STILL_ACTIVE,
        /** Count the hire, and leave the driver out of the active line entirely. */
        EXCLUDE_FROM_ACTIVE
    }

    private String apiUrl = "https://api.monday.com/v2";
    private String apiVersion = "2024-10";
    private String token = "";

    /**
     * One board per company, keyed by entity name — { JM}, { BP}.
     *
     * <p>Separate boards, not one board with a company column: the two teams hire independently
     * and a recruiter appears on exactly one of them. The same API token reads both unless an
     * entry in { #tokens} says otherwise.
     */
    private Map<String, Long> boards = new LinkedHashMap<>();

    /** Per-company token overrides. Empty when one token reads every board, which it does today. */
    private Map<String, String> tokens = new LinkedHashMap<>();

    /**
     * Per-company column id overrides.
     *
     * <p>Column ids are assigned per board, not per account. JM and BP happen to share the Source
     * and Date ids because one board was copied from the other, but their termination columns
     * differ — and a column id that does not exist on a board reads as empty rather than as an
     * error, so getting this wrong shows every driver as never terminated.
     */
    private Map<String, Columns> columns = new LinkedHashMap<>();

    /** The three columns the chart is built from, for one board. */
    public static class Columns {
        private String sourceColumnId;
        private String hireDateColumnId;
        private String terminationDateColumnId;

        public String getSourceColumnId() { return sourceColumnId; }
        public void setSourceColumnId(String v) { this.sourceColumnId = v; }
        public String getHireDateColumnId() { return hireDateColumnId; }
        public void setHireDateColumnId(String v) { this.hireDateColumnId = v; }
        public String getTerminationDateColumnId() { return terminationDateColumnId; }
        public void setTerminationDateColumnId(String v) { this.terminationDateColumnId = v; }
    }

    /** Group titles, matched case-insensitively. */
    private String activeGroup = "Loaded";
    private String terminatedGroup = "Terminated";

    private String sourceColumnId = "color_mkra3nmw";
    private String hireDateColumnId = "date_mkrbh5r0";
    private String terminationDateColumnId = "date_mm5gztc7";

    private UnknownTerminationPolicy unknownTermination = UnknownTerminationPolicy.USE_UPDATED_AT;

    /**
     * How long a board read is reused. The board changes a few times a day and the chart is a
     * management view, so minutes of staleness cost nothing and spare monday the traffic.
     */
    private Duration cacheTtl = Duration.ofMinutes(10);

    private Duration requestTimeout = Duration.ofSeconds(20);

    /** Items per GraphQL page. monday caps this at 500. */
    private int pageSize = 100;

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Map<String, Long> getBoards() { return boards; }
    public void setBoards(Map<String, Long> boards) { this.boards = boards; }
    public Map<String, String> getTokens() { return tokens; }
    public void setTokens(Map<String, String> tokens) { this.tokens = tokens; }

    public Map<String, Columns> getColumns() { return columns; }
    public void setColumns(Map<String, Columns> columns) { this.columns = columns; }

    /** The column ids for one board, each falling back to the shared value. */
    public Columns columnsFor(String entity) {
        Columns override = columns.get(entity);
        Columns resolved = new Columns();
        resolved.setSourceColumnId(pick(override == null ? null : override.getSourceColumnId(),
                sourceColumnId));
        resolved.setHireDateColumnId(pick(override == null ? null : override.getHireDateColumnId(),
                hireDateColumnId));
        resolved.setTerminationDateColumnId(
                pick(override == null ? null : override.getTerminationDateColumnId(),
                        terminationDateColumnId));
        return resolved;
    }

    private static String pick(String override, String shared) {
        return override == null || override.isBlank() ? shared : override;
    }

    /** The token for one company, falling back to the shared one. */
    public String tokenFor(String entity) {
        String override = tokens.get(entity);
        return override == null || override.isBlank() ? token : override;
    }
    public String getActiveGroup() { return activeGroup; }
    public void setActiveGroup(String activeGroup) { this.activeGroup = activeGroup; }
    public String getTerminatedGroup() { return terminatedGroup; }
    public void setTerminatedGroup(String terminatedGroup) { this.terminatedGroup = terminatedGroup; }
    public String getSourceColumnId() { return sourceColumnId; }
    public void setSourceColumnId(String sourceColumnId) { this.sourceColumnId = sourceColumnId; }
    public String getHireDateColumnId() { return hireDateColumnId; }
    public void setHireDateColumnId(String hireDateColumnId) { this.hireDateColumnId = hireDateColumnId; }
    public String getTerminationDateColumnId() { return terminationDateColumnId; }
    public void setTerminationDateColumnId(String id) { this.terminationDateColumnId = id; }
    public UnknownTerminationPolicy getUnknownTermination() { return unknownTermination; }
    public void setUnknownTermination(UnknownTerminationPolicy p) { this.unknownTermination = p; }
    public Duration getCacheTtl() { return cacheTtl; }
    public void setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
}
