package uz.drenix.identity.performance.ringcentral;

import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the RingCentral account, and the daily bar recruiters are held to.
 *
 * <p>The standard lives here rather than in code so it can be changed without a release. It is a
 * management target, and management targets move.
 */
@ConfigurationProperties(prefix = "drenix.ringcentral")
public class RingCentralProperties {

    private String baseUrl = "https://platform.ringcentral.com";

    /**
     * One RingCentral account per company, keyed by entity name — { JM}, { BP}.
     *
     * <p>They are genuinely separate accounts, not two views of one: a number that exists in JM
     * does not exist in BP, the call logs are disjoint, and each has its own request budget. That
     * is why the client is built per account rather than parameterised per call.
     */
    private Map<String, Account> accounts = new LinkedHashMap<>();

    /**
     * The account used for anybody whose role grants name no company at all.
     *
     * <p>Somebody has to own an unscoped account's numbers, and silently reporting nothing for
     * them would look like a person who did no work.
     */
    private String defaultEntity = "JM";

    /** Credentials for one company's RingCentral account. */
    public static class Account {
        private String clientId = "";
        private String clientSecret = "";
        /** The JWT credential. Exchanged for a short-lived access token on each refresh. */
        private String jwt = "";

        public boolean isConfigured() {
            return !clientId.isBlank() && !jwt.isBlank();
        }

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getJwt() { return jwt; }
        public void setJwt(String jwt) { this.jwt = jwt; }
    }

    /** Every call attempt counts, in either direction, connected or not. */
    private int callsPerDay = 125;
    /** Total time on calls in a day, summed across every call. */
    private Duration talkPerDay = Duration.ofHours(1);
    /** Outbound only — a reply the recruiter received is not work they did. */
    private int smsSentPerDay = 150;

    /**
     * Length of a full shift. Only used to scale the targets when a caller asks about part of a
     * day: 18:00-03:00 is nine hours of nine, so the full target applies; three hours of nine
     * carries a third of it.
     */
    private Duration shift = Duration.ofHours(9);

    /**
     * The clock window a shift covers. Used by the daily snapshot, which has to pick a window
     * without anybody asking; the live report still takes whatever the caller passes.
     */
    private LocalTime shiftStart = LocalTime.of(18, 0);
    private LocalTime shiftEnd = LocalTime.of(3, 0);

    /**
     * The zone dates, shift windows and hour labels are expressed in.
     *
     * <p>UTC by default, because that is what every figure this service has ever reported was
     * grouped by, and silently re-cutting existing days would make yesterday's numbers disagree
     * with themselves. Set {@code RC_ZONE} to the office's zone to make the shift window mean
     * office hours.
     */
    private ZoneId zone = ZoneOffset.UTC;

    /**
     * How long a fetched range is reused. The call log is append-only and the chart is a management
     * view, so minutes of staleness cost nothing and spare RingCentral the traffic.
     */
    private Duration cacheTtl = Duration.ofMinutes(10);

    /** Refuse ranges longer than this: each extra day is another page of call log to walk. */
    private int maxRangeDays = 31;

    private Duration requestTimeout = Duration.ofSeconds(30);

    /**
     * RingCentral's budget for the "heavy" group the call log and message store belong to. The
     * account reports it on every response as {@code x-rate-limit-limit} over
     * {@code x-rate-limit-window}; ten per minute at the time of writing.
     */
    private int rateLimitPermits = 10;
    private Duration rateLimitWindow = Duration.ofSeconds(60);

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Map<String, Account> getAccounts() { return accounts; }
    public void setAccounts(Map<String, Account> accounts) { this.accounts = accounts; }
    public String getDefaultEntity() { return defaultEntity; }
    public void setDefaultEntity(String defaultEntity) { this.defaultEntity = defaultEntity; }
    public int getCallsPerDay() { return callsPerDay; }
    public void setCallsPerDay(int callsPerDay) { this.callsPerDay = callsPerDay; }
    public Duration getTalkPerDay() { return talkPerDay; }
    public void setTalkPerDay(Duration talkPerDay) { this.talkPerDay = talkPerDay; }
    public int getSmsSentPerDay() { return smsSentPerDay; }
    public void setSmsSentPerDay(int smsSentPerDay) { this.smsSentPerDay = smsSentPerDay; }
    public Duration getCacheTtl() { return cacheTtl; }
    public void setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; }
    public int getMaxRangeDays() { return maxRangeDays; }
    public void setMaxRangeDays(int maxRangeDays) { this.maxRangeDays = maxRangeDays; }
    public LocalTime getShiftStart() { return shiftStart; }
    public void setShiftStart(LocalTime shiftStart) { this.shiftStart = shiftStart; }
    public LocalTime getShiftEnd() { return shiftEnd; }
    public void setShiftEnd(LocalTime shiftEnd) { this.shiftEnd = shiftEnd; }
    public Duration getShift() { return shift; }
    public void setShift(Duration shift) { this.shift = shift; }
    public ZoneId getZone() { return zone; }
    public void setZone(ZoneId zone) { this.zone = zone; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public int getRateLimitPermits() { return rateLimitPermits; }
    public void setRateLimitPermits(int rateLimitPermits) { this.rateLimitPermits = rateLimitPermits; }
    public Duration getRateLimitWindow() { return rateLimitWindow; }
    public void setRateLimitWindow(Duration w) { this.rateLimitWindow = w; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
}
