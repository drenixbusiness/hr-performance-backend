package uz.drenix.identity.performance.ringcentral;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Reads call and message activity from RingCentral.
 *
 * <p>Two very different shapes sit behind this class. The <b>call log</b> is available for the
 * whole account in one paged query, so every recruiter's calls arrive together. <b>Messages</b>
 * are not: RingCentral exposes no account-level message store, only one per extension, so SMS has
 * to be fetched per person.
 *
 * <p>That asymmetry has a consequence worth knowing. Extension ids are discovered from the call
 * log — this app is not granted {@code ReadAccounts}, so it cannot list extensions directly. A
 * recruiter who sent messages but made no calls in the range therefore cannot be found at all, and
 * is reported with {@code extensionUnresolved} rather than as a silent zero.
 */
public class RingCentralClient {

    private static final Logger log = LoggerFactory.getLogger(RingCentralClient.class);

    /**
     * One call, reduced to what the standard measures.
     *
     * <p>{@code startedAt} rather than a date: a shift that runs 18:00 to 03:00 needs the clock
     * time, and which day a call belongs to depends on the reporting zone anyway.
     */
    public record CallRecord(String extensionId, String phoneNumber, String agentName,
                             Instant startedAt, int durationSeconds) {
    }

    private final RingCentralProperties properties;
    private final RingCentralProperties.Account account;
    /** The company this client reads, for log lines that would otherwise be ambiguous. */
    private final String entity;
    private final RestClient restClient;
    private final AtomicReference<AccessToken> token = new AtomicReference<>();
    private final RateLimiter limiter;

    private record AccessToken(String value, Instant expiresAt) {
        boolean usable() {
            // A minute of headroom: a token that expires mid-request is a failure that looks random.
            return Instant.now().isBefore(expiresAt.minusSeconds(60));
        }
    }

    /**
     * One client per RingCentral account.
     *
     * <p>The token cache and the rate limiter are per instance because both are per account:
     * RingCentral counts requests against the account, so one shared limiter would throttle JM
     * for traffic BP generated, and one shared token would authenticate to the wrong company.
     */
    public RingCentralClient(RingCentralProperties properties,
                             RingCentralProperties.Account account,
                             String entity) {
        this.properties = properties;
        this.account = account;
        this.entity = entity;
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
        this.limiter = new RateLimiter(properties.getRateLimitPermits(), properties.getRateLimitWindow());
    }

    /** Every call in the range, for the whole account. */
    public List<CallRecord> calls(LocalDate from, LocalDate to) {
        List<CallRecord> records = new ArrayList<>();
        String bearer = bearer();
        int page = 1;
        // RingCentral caps a page at 1000; the loop stops early on a short page.
        while (page <= 200) {
            Map<String, Object> body = get("/restapi/v1.0/account/~/call-log", bearer, Map.of(
                    "perPage", "1000",
                    "page", String.valueOf(page),
                    "view", "Detailed",
                    "dateFrom", startOfDay(from),
                    "dateTo", endOfDay(to)));

            List<Map<String, Object>> rows = records(body);
            rows.stream().map(RingCentralClient::toCall).filter(java.util.Objects::nonNull)
                    .forEach(records::add);
            if (rows.size() < 1000) {
                break;
            }
            page++;
        }
        log.info("Read {} RingCentral call records for {} {}..{}", records.size(), entity, from, to);
        return records;
    }


    /**
     * Every direct number on the account, mapped to the extension that owns it.
     *
     * <p>This is what makes a number that only ever sends messages findable. Extensions used to be
     * discovered from the call log, which meant a recruiter who spent a day on SMS and made no
     * calls could not be identified at all, and their messages were reported as zero — the numbers
     * were not missing, they were never looked for.
     *
     * <p>Needs the {@code ReadAccounts} scope. When the app is not granted it, this comes back
     * empty and the caller falls back to the call log, which is what the whole service used to do.
     */
    public Map<String, String> extensionsByDirectNumber() {
        Map<String, String> byNumber = new java.util.HashMap<>();
        String bearer = bearer();
        int page = 1;
        while (page <= 50) {
            Map<String, Object> body;
            try {
                body = get("/restapi/v1.0/account/~/phone-number", bearer, Map.of(
                        "perPage", "1000",
                        "page", String.valueOf(page),
                        "usageType", "DirectNumber"));
            } catch (HttpClientErrorException e) {
                // Almost always a missing ReadAccounts scope. Not fatal: the call log still
                // resolves anyone who made a call, which is how this worked before.
                log.warn("Could not read the RingCentral number directory ({}); "
                         + "falling back to call-log discovery", e.getStatusCode());
                return Map.of();
            }

            List<Map<String, Object>> rows = records(body);
            for (Map<String, Object> row : rows) {
                String number = str(row.get("phoneNumber"));
                @SuppressWarnings("unchecked")
                Map<String, Object> extension = (Map<String, Object>) row.get("extension");
                if (number == null || extension == null || extension.get("id") == null) {
                    // A number with no extension is a main line or a fax, not a person.
                    continue;
                }
                String key = PhoneNumbers.key(number);
                if (key != null) {
                    byNumber.put(key, String.valueOf(extension.get("id")));
                }
            }
            if (rows.size() < 1000) {
                break;
            }
            page++;
        }
        log.info("Read {} RingCentral direct numbers for {}", byNumber.size(), entity);
        return byNumber;
    }

    /**
     * When each outbound SMS was sent, for one extension.
     *
     * <p>Instants rather than a per-day tally, so the caller can cut them by clock time. The list
     * is unsorted; nothing downstream depends on order.
     */
    public List<Instant> outboundSmsTimes(String extensionId, LocalDate from, LocalDate to) {
        List<Instant> sent = new ArrayList<>();
        String bearer = bearer();
        int page = 1;
        while (page <= 200) {
            Map<String, Object> body = get(
                    "/restapi/v1.0/account/~/extension/" + extensionId + "/message-store", bearer,
                    Map.of("perPage", "1000",
                           "page", String.valueOf(page),
                           "messageType", "SMS",
                           "direction", "Outbound",
                           "dateFrom", startOfDay(from),
                           "dateTo", endOfDay(to)));

            List<Map<String, Object>> rows = records(body);
            for (Map<String, Object> row : rows) {
                Instant at = instant(String.valueOf(row.get("creationTime")));
                if (at != null) {
                    sent.add(at);
                }
            }
            if (rows.size() < 1000) {
                break;
            }
            page++;
        }
        return sent;
    }

    private static CallRecord toCall(Map<String, Object> row) {
        Instant startedAt = instant(String.valueOf(row.get("startTime")));
        if (startedAt == null) {
            return null;
        }
        int duration = row.get("duration") instanceof Number n ? n.intValue() : 0;

        @SuppressWarnings("unchecked")
        Map<String, Object> from = (Map<String, Object>) row.get("from");
        @SuppressWarnings("unchecked")
        Map<String, Object> to = (Map<String, Object>) row.get("to");
        @SuppressWarnings("unchecked")
        Map<String, Object> extension = (Map<String, Object>) row.get("extension");

        // Outbound puts the agent in `from`; inbound puts them in `to`, and `to` carries no name or
        // extension id, so the top-level `extension` block is the only identifier that spans both.
        boolean outbound = "Outbound".equals(row.get("direction"));
        Map<String, Object> agentSide = outbound ? from : to;

        String phone = agentSide == null ? null : str(agentSide.get("phoneNumber"));
        String name = from == null ? null : str(from.get("name"));
        String extensionId = extension != null && extension.get("id") != null
                ? String.valueOf(extension.get("id"))
                : (from == null ? null : str(from.get("extensionId")));

        if (phone == null && extensionId == null) {
            return null;
        }
        return new CallRecord(extensionId, phone, outbound ? name : null, startedAt, duration);
    }

    /**
     * One rate-limited GET, retried once if RingCentral says the budget is spent anyway.
     *
     * <p>The limiter should prevent a 429 outright, but the budget is per account rather than per
     * process: anything else using the same credentials spends from the same allowance. One retry
     * that honours {@code Retry-After} covers that without turning a busy minute into a failed
     * page load.
     */
    private Map<String, Object> get(String path, String bearer, Map<String, String> query) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        query.forEach(params::add);

        for (int attempt = 1; ; attempt++) {
            limiter.acquire();
            try {
                Map<String, Object> body = restClient.get()
                        .uri(uriBuilder -> uriBuilder.path(path).queryParams(params).build())
                        .header("Authorization", "Bearer " + bearer)
                        .retrieve()
                        .body(Map.class);

                if (body == null) {
                    throw new RingCentralUnavailableException("Empty response from RingCentral", null);
                }
                return body;
            } catch (HttpClientErrorException.TooManyRequests e) {
                Duration wait = retryAfter(e);
                limiter.penalise(wait);
                if (attempt >= 2) {
                    throw new RingCentralRateLimitedException(
                            "RingCentral is rate limiting this account", wait, e);
                }
                log.warn("RingCentral rate limit hit on {}; waiting {}s before one retry",
                        path, wait.toSeconds());
            }
        }
    }

    private static Duration retryAfter(HttpClientErrorException e) {
        String header = e.getResponseHeaders() == null
                ? null
                : e.getResponseHeaders().getFirst("Retry-After");
        try {
            return header == null ? Duration.ofSeconds(60) : Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException ignored) {
            return Duration.ofSeconds(60);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> body) {
        Object records = body.get("records");
        return records instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /**
     * A valid bearer token, minted from the JWT when the cached one is spent.
     *
     * <p>RingCentral tokens last an hour. Fetching one per request would work and would also be a
     * needless round trip on every call.
     */
    private String bearer() {
        AccessToken current = token.get();
        if (current != null && current.usable()) {
            return current.value();
        }
        if (!account.isConfigured()) {
            throw new RingCentralUnavailableException(
                    "No RingCentral credentials are configured for " + entity, null);
        }

        String basic = Base64.getEncoder().encodeToString(
                (account.getClientId() + ":" + account.getClientSecret())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", account.getJwt());

        try {
            Map<String, Object> body = restClient.post()
                    .uri("/restapi/oauth/token")
                    .header("Authorization", "Basic " + basic)
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            String value = body == null ? null : str(body.get("access_token"));
            if (value == null) {
                throw new RingCentralUnavailableException("RingCentral refused the JWT credential", null);
            }
            long ttl = body.get("expires_in") instanceof Number n ? n.longValue() : 3600L;
            AccessToken fresh = new AccessToken(value, Instant.now().plusSeconds(ttl));
            token.set(fresh);
            return value;
        } catch (RingCentralUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RingCentralUnavailableException("Could not obtain a RingCentral access token", e);
        }
    }

    // Day boundaries are taken in the reporting zone, not in UTC, so a range asked for in local
    // terms is fetched in local terms. With the default zone of UTC these are the same thing.
    private String startOfDay(LocalDate day) {
        return day.atStartOfDay(properties.getZone()).toInstant().toString();
    }

    private String endOfDay(LocalDate day) {
        return day.plusDays(1).atStartOfDay(properties.getZone()).toInstant().minusMillis(1).toString();
    }

    private static Instant instant(String isoInstant) {
        try {
            return Instant.parse(isoInstant);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /** Visible for the calculator, which needs the same window arithmetic. */
    public Duration cacheTtl() {
        return properties.getCacheTtl();
    }
}
