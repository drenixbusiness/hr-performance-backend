package uz.drenix.identity.performance.ringcentral;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import uz.drenix.identity.performance.ringcentral.RingCentralClient.CallRecord;

/**
 * Turns raw RingCentral traffic into one row per recruiter per shift, judged against the standard.
 *
 * <p>Four decisions are baked in, and each one was a choice rather than the obvious reading.
 *
 * <p><b>Every call attempt counts.</b> A number that rang out and a conversation both count as one
 * call, because the target measures effort made rather than luck with who picked up.
 *
 * <p><b>Only outbound SMS counts.</b> Replies a recruiter received are not work they did, and
 * counting them would let a chatty candidate lift somebody's numbers.
 *
 * <p><b>Talk time is the shift's total</b>, not an average per call — an hour on the phone across
 * however many calls it took.
 *
 * <p><b>A shift belongs to the day it started on.</b> Somebody working 18:00 to 03:00 does one
 * night's work, not two half-days, and splitting it at midnight would show every recruiter missing
 * every target twice over. See {@link Window}.
 */
@Service
public class ActivityService {

    /** The targets in force for one request. Passed in, because they are editable. */
    public record Standard(int callsPerDay, int talkSecondsPerDay, int smsSentPerDay,
                           Duration shift) {
    }

    /** One clock hour inside a shift. */
    public record HourPoint(LocalDateTime hour, int calls, int talkSeconds, int smsSent) {
    }

    public record DayPoint(LocalDate date, int calls, int talkSeconds, int smsSent,
                           List<HourPoint> hours) {
    }

    /**
     * One person, added up across every number they work.
     *
     *  unresolvedPhones numbers that never appeared in the call log, so nothing could be
     *                         read for them. Named rather than counted as zero, because a silent
     *                         zero reads as poor performance.
     */
    public record AgentActivity(
            String agentId,
            List<String> ringCentralPhones,
            List<String> unresolvedPhones,
            String displayName,
            String extensionId,
            List<DayPoint> days,
            boolean extensionUnresolved) {

        /** The first number, for callers written before a person could have several. */
        public String ringCentralPhone() {
            return ringCentralPhones.isEmpty() ? null : ringCentralPhones.getFirst();
        }

        public int totalCalls() {
            return days.stream().mapToInt(DayPoint::calls).sum();
        }

        public long totalTalkSeconds() {
            return days.stream().mapToLong(DayPoint::talkSeconds).sum();
        }

        public int totalSmsSent() {
            return days.stream().mapToInt(DayPoint::smsSent).sum();
        }

        /** Shifts on which anything happened. Leave should not be averaged in as failure. */
        public int activeDays() {
            return (int) days.stream()
                    .filter(d -> d.calls() > 0 || d.smsSent() > 0 || d.talkSeconds() > 0)
                    .count();
        }
    }

    /**
     * The stretch of real time one report row covers.
     *
     * <p>Half-open: {@code [start, end)}. A call that begins exactly at 03:00 belongs to the next
     * shift, not to two of them.
     */
    public record Window(LocalDate day, Instant start, Instant end) {
        boolean contains(Instant at) {
            return !at.isBefore(start) && at.isBefore(end);
        }
    }

    /**
     * What the caller asked for, resolved into concrete windows.
     *
     * @param wholeDay true when no clock times were given, so each window is a calendar day
     */
    public record Report(List<AgentActivity> agents, List<Window> windows, boolean wholeDay,
                         Duration windowLength) {
    }

    private record CacheKey(String entity, LocalDate from, LocalDate to) {
    }

    private record CachedCalls(List<CallRecord> calls, Instant readAt) {
    }

    private record CachedSms(List<Instant> sent, Instant readAt) {
    }

    private record CachedDirectory(Map<String, String> byNumber, Instant readAt) {
    }

    /** Extensions are handed out when somebody joins, not through the day. */
    private static final Duration DIRECTORY_TTL = Duration.ofHours(1);

    private final RingCentralAccounts accounts;
    private final RingCentralProperties properties;
    private final Map<CacheKey, CachedCalls> callCache = new ConcurrentHashMap<>();
    private final Map<String, CachedSms> smsCache = new ConcurrentHashMap<>();
    private final Map<String, CachedDirectory> directories = new ConcurrentHashMap<>();

    public ActivityService(RingCentralAccounts accounts, RingCentralProperties properties) {
        this.accounts = accounts;
        this.properties = properties;
    }

    /**
     * One person, the numbers their work is spread across, and the company whose RingCentral
     * account those numbers live in.
     *
     * <p>The entity is not decoration. JM and BP are separate accounts, and looking a BP number up
     * in the JM account finds nothing at all — the person would come back with every figure at
     * zero and no indication that the wrong account had been asked.
     */
    public record AgentKey(String id, List<String> phones, String entity) {
    }

    /**
     * @param agents   who to report on, already grouped. The caller has decided both who the
     *                 requester may see and which numbers belong to the same person; this method
     *                 reports on exactly those groups and no more.
     * @param fromTime start of the clock window, or {@code null} for whole days
     * @param toTime   end of the clock window, exclusive. When it is not after {@code fromTime} the
     *                 window runs past midnight and closes on the following day.
     */
    public Report activity(List<AgentKey> agents, LocalDate from, LocalDate to,
                           LocalTime fromTime, LocalTime toTime) {

        long dayCount = ChronoUnit.DAYS.between(from, to) + 1;
        if (dayCount < 1) {
            throw new IllegalArgumentException("The end date is before the start date");
        }
        if (dayCount > properties.getMaxRangeDays()) {
            throw new IllegalArgumentException(
                    "Range is longer than " + properties.getMaxRangeDays() + " days");
        }
        if ((fromTime == null) != (toTime == null)) {
            throw new IllegalArgumentException(
                    "fromTime and toTime must be given together, or neither");
        }

        boolean wholeDay = fromTime == null;
        List<Window> windows = windows(from, to, fromTime, toTime);
        Duration windowLength = Duration.between(windows.getFirst().start(), windows.getFirst().end());

        if (agents.isEmpty()) {
            return new Report(List.of(), windows, wholeDay, windowLength);
        }

        // A window that runs past midnight spills into the day after the last one asked for, so
        // the fetch has to reach further than the report does. `end` is exclusive, hence the step
        // back: without it a plain whole-day range would fetch one day more than it reports.
        LocalDate fetchTo = windows.getLast().end().minusMillis(1).atZone(zone()).toLocalDate();
        if (fetchTo.isBefore(to)) {
            fetchTo = to;
        }
        final LocalDate fetchEnd = fetchTo;

        // Grouped by company, because each is a separate RingCentral account: separate
        // credentials, a disjoint call log, and a request budget of its own. Asking JM's account
        // about a BP number does not fail — it quietly finds nothing, which is worse.
        Map<String, List<AgentKey>> byEntity = new LinkedHashMap<>();
        for (AgentKey agent : agents) {
            byEntity.computeIfAbsent(accounts.resolve(agent.entity()), k -> new ArrayList<>())
                    .add(agent);
        }

        Map<String, AgentActivity> results = new HashMap<>();
        for (Map.Entry<String, List<AgentKey>> company : byEntity.entrySet()) {
            measure(company.getKey(), company.getValue(), from, fetchEnd, windows, wholeDay)
                    .forEach(a -> results.put(a.agentId(), a));
        }

        // Emitted in the order they were asked for, not in company order: the caller built the
        // list, and a response that silently reshuffles it is a trap for anyone zipping the two.
        List<AgentActivity> result = new ArrayList<>(agents.size());
        for (AgentKey agent : agents) {
            AgentActivity measured = results.get(agent.id());
            if (measured != null) {
                result.add(measured);
            }
        }
        return new Report(result, windows, wholeDay, windowLength);
    }

    /** Everything for one company, read from that company's own RingCentral account. */
    private List<AgentActivity> measure(String entity, List<AgentKey> agents,
                                        LocalDate from, LocalDate fetchEnd,
                                        List<Window> windows, boolean wholeDay) {

        List<CallRecord> calls = cachedCalls(entity, from, fetchEnd);

        // Index the account's calls once, rather than scanning the whole log per person. Two
        // indexes, because the two ways of recognising a call do not always agree: `extension` is
        // the account extension the call belongs to, while `phoneNumber` is the caller id, and an
        // extension placing calls under a shared company caller id shows a number nobody owns.
        Map<String, List<CallRecord>> byPhone = new HashMap<>();
        Map<String, List<CallRecord>> byExtension = new HashMap<>();
        Map<String, String> extensionByPhone = new HashMap<>();
        Map<String, String> nameByPhone = new HashMap<>();
        for (CallRecord call : calls) {
            if (call.extensionId() != null) {
                byExtension.computeIfAbsent(call.extensionId(), k -> new ArrayList<>()).add(call);
            }
            String key = PhoneNumbers.key(call.phoneNumber());
            if (key == null) {
                continue;
            }
            byPhone.computeIfAbsent(key, k -> new ArrayList<>()).add(call);
            if (call.extensionId() != null) {
                extensionByPhone.putIfAbsent(key, call.extensionId());
            }
            if (call.agentName() != null) {
                nameByPhone.putIfAbsent(key, call.agentName());
            }
        }

        // Who owns which number, straight from this company's account. The call log is only a
        // fallback: it can name an extension for somebody who made calls, and nobody else.
        Map<String, String> numberOwners = cachedDirectory(entity);

        List<AgentActivity> result = new ArrayList<>(agents.size());
        for (AgentKey agent : agents) {
            // One person's traffic, gathered from every number they work. A lead on two
            // extensions is one row: two half-rows would show them missing every target twice.
            //
            // Identity-based, because the same call can be reached through both indexes and a
            // record equal to another by value is still a second call that really happened.
            Set<CallRecord> own =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            List<Instant> sms = new ArrayList<>();
            List<String> unresolved = new ArrayList<>();
            Set<String> extensions = new java.util.LinkedHashSet<>();
            String displayName = null;

            for (String phone : agent.phones()) {
                String key = PhoneNumbers.key(phone);
                if (key == null) {
                    unresolved.add(phone);
                    continue;
                }
                own.addAll(byPhone.getOrDefault(key, List.of()));
                if (displayName == null) {
                    displayName = nameByPhone.get(key);
                }

                String extension = numberOwners.get(key);
                if (extension == null) {
                    extension = extensionByPhone.get(key);
                }
                if (extension == null) {
                    // Not on this company's account and never in its call log: a number this
                    // person does not work, one typed wrong, or one belonging to the other company.
                    unresolved.add(phone);
                    continue;
                }
                extensions.add(extension);
            }

            // Once per extension, not once per number: two numbers on one extension share a
            // message store, and reading it twice would double every SMS.
            for (String extension : extensions) {
                own.addAll(byExtension.getOrDefault(extension, List.of()));
                sms.addAll(cachedSms(entity, extension, from, fetchEnd));
            }

            result.add(new AgentActivity(
                    agent.id(),
                    agent.phones(),
                    List.copyOf(unresolved),
                    displayName,
                    extensions.isEmpty() ? null : extensions.iterator().next(),
                    buildDays(new ArrayList<>(own), sms, windows, wholeDay),
                    // Only when nothing at all could be found. One of two numbers resolving still
                    // gives a row worth reading, and unresolvedPhones says what is missing from it.
                    extensions.isEmpty()));
        }
        return result;
    }

    /**
     * One window per day in the range.
     *
     * <p>Every window is labelled with the day it opened on, which is what makes a night shift one
     * row instead of two.
     */
    private List<Window> windows(LocalDate from, LocalDate to, LocalTime fromTime, LocalTime toTime) {
        List<Window> windows = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (fromTime == null) {
                windows.add(new Window(day,
                        day.atStartOfDay(zone()).toInstant(),
                        day.plusDays(1).atStartOfDay(zone()).toInstant()));
                continue;
            }
            // `to` not after `from` is how a caller says "past midnight": 18:00 to 03:00, and also
            // 00:00 to 00:00 for a full 24 hours broken down by hour.
            LocalDate endDay = toTime.isAfter(fromTime) ? day : day.plusDays(1);
            windows.add(new Window(day,
                    day.atTime(fromTime).atZone(zone()).toInstant(),
                    endDay.atTime(toTime).atZone(zone()).toInstant()));
        }
        return windows;
    }

    private List<DayPoint> buildDays(List<CallRecord> calls, List<Instant> sms,
                                     List<Window> windows, boolean wholeDay) {
        // Windows never overlap and are in order, so the one a moment falls in is the last one
        // that opened at or before it. floorEntry finds that in log time rather than by scanning
        // every day for every record.
        TreeMap<Instant, Window> byStart = new TreeMap<>();
        Map<LocalDate, int[]> tally = new HashMap<>();
        Map<LocalDate, Map<LocalDateTime, int[]>> hourly = new HashMap<>();
        for (Window window : windows) {
            byStart.put(window.start(), window);
            tally.put(window.day(), new int[] {0, 0, 0});   // calls, talkSeconds, smsSent
            hourly.put(window.day(), new HashMap<>());
        }

        for (CallRecord call : calls) {
            Window window = windowFor(byStart, call.startedAt());
            if (window == null) {
                continue;
            }
            int[] row = tally.get(window.day());
            row[0]++;
            row[1] += call.durationSeconds();
            int[] hour = hourRow(hourly, window.day(), call.startedAt());
            hour[0]++;
            hour[1] += call.durationSeconds();
        }

        for (Instant at : sms) {
            Window window = windowFor(byStart, at);
            if (window == null) {
                continue;
            }
            tally.get(window.day())[2]++;
            hourRow(hourly, window.day(), at)[2]++;
        }

        List<DayPoint> points = new ArrayList<>(windows.size());
        for (Window window : windows) {
            int[] row = tally.get(window.day());
            // Hours are only broken out when a clock window was asked for. A month of whole days
            // would be 744 points per person, which is a lot of response for nobody's question.
            List<HourPoint> hours = wholeDay
                    ? List.of()
                    : hours(window, hourly.get(window.day()));
            points.add(new DayPoint(window.day(), row[0], row[1], row[2], hours));
        }
        return points;
    }

    /** Every hour the window touches, including the empty ones, so a chart has no gaps. */
    private List<HourPoint> hours(Window window, Map<LocalDateTime, int[]> counted) {
        List<HourPoint> hours = new ArrayList<>();
        LocalDateTime cursor = LocalDateTime.ofInstant(window.start(), zone())
                .truncatedTo(ChronoUnit.HOURS);
        LocalDateTime last = LocalDateTime.ofInstant(window.end().minusMillis(1), zone())
                .truncatedTo(ChronoUnit.HOURS);
        while (!cursor.isAfter(last)) {
            int[] row = counted.getOrDefault(cursor, new int[] {0, 0, 0});
            hours.add(new HourPoint(cursor, row[0], row[1], row[2]));
            cursor = cursor.plusHours(1);
        }
        return hours;
    }

    private int[] hourRow(Map<LocalDate, Map<LocalDateTime, int[]>> hourly, LocalDate day,
                          Instant at) {
        LocalDateTime hour = LocalDateTime.ofInstant(at, zone()).truncatedTo(ChronoUnit.HOURS);
        return hourly.get(day).computeIfAbsent(hour, h -> new int[] {0, 0, 0});
    }

    private static Window windowFor(TreeMap<Instant, Window> byStart, Instant at) {
        var entry = byStart.floorEntry(at);
        return entry != null && entry.getValue().contains(at) ? entry.getValue() : null;
    }

    private ZoneId zone() {
        return properties.getZone();
    }

    private List<CallRecord> cachedCalls(String entity, LocalDate from, LocalDate to) {
        CacheKey key = new CacheKey(entity, from, to);
        CachedCalls cached = callCache.get(key);
        if (cached != null && fresh(cached.readAt())) {
            return cached.calls();
        }
        List<CallRecord> calls = accounts.forEntity(entity).calls(from, to);
        callCache.put(key, new CachedCalls(calls, Instant.now()));
        return calls;
    }

    /**
     * One company's number-to-extension map, kept for an hour.
     *
     * <p>An hour rather than the ten minutes the traffic caches get: extensions are handed out
     * when somebody joins, not through the day, and this is a request out of the same small
     * budget every call log page competes for.
     */
    private Map<String, String> cachedDirectory(String entity) {
        CachedDirectory cached = directories.get(entity);
        if (cached != null
                && Duration.between(cached.readAt(), Instant.now()).compareTo(DIRECTORY_TTL) < 0) {
            return cached.byNumber();
        }
        Map<String, String> fresh = accounts.forEntity(entity).extensionsByDirectNumber();
        directories.put(entity, new CachedDirectory(fresh, Instant.now()));
        return fresh;
    }

    private List<Instant> cachedSms(String entity, String extensionId,
                                    LocalDate from, LocalDate to) {
        // The entity is part of the key: extension ids are only unique within one account, and
        // two companies could easily both have an extension 101.
        String key = entity + "|" + extensionId + "|" + from + "|" + to;
        CachedSms cached = smsCache.get(key);
        if (cached != null && fresh(cached.readAt())) {
            return cached.sent();
        }
        List<Instant> sent = accounts.forEntity(entity).outboundSmsTimes(extensionId, from, to);
        smsCache.put(key, new CachedSms(sent, Instant.now()));
        return sent;
    }

    private boolean fresh(Instant readAt) {
        return Duration.between(readAt, Instant.now()).compareTo(properties.getCacheTtl()) < 0;
    }

    /** The standard from configuration, used when the caller did not supply one. */
    public Standard configuredStandard() {
        return new Standard(
                properties.getCallsPerDay(),
                (int) properties.getTalkPerDay().toSeconds(),
                properties.getSmsSentPerDay(),
                properties.getShift());
    }

    public ZoneId reportingZone() {
        return properties.getZone();
    }
}
