package uz.drenix.identity.notification.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uz.drenix.identity.grpc.v1.AuditEntry;
import uz.drenix.identity.grpc.v1.ListAuditRequest;
import uz.drenix.identity.grpc.v1.PageRequest;
import uz.drenix.identity.grpc.v1.User;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;
import uz.drenix.identity.notification.client.Directory;
import uz.drenix.identity.notification.config.NotificationProperties;

/**
 * Turns a few specific audit actions into notifications.
 *
 * <p><b>Pulled, not pushed.</b> user-service could call this service every time it audits
 * something, and then a notification outage would fail user creation. Polling inverts that: the
 * worst this service can do while broken is fall behind, and it catches up by itself when it
 * returns. The audit log is already the durable record, so it needs no queue of its own.
 *
 * <p><b>Not everything in the audit log is worth telling somebody about.</b> The first version
 * notified on every entry, and inboxes filled with sign-ins nobody reads — which is how a
 * notification list teaches people to ignore it. Only the actions in {@link #NOTIFIED} produce a
 * notification; the audit log remains the place to go for the rest.
 *
 * <p><b>Addressed by who was affected, not by who acted.</b> A notification about an account goes
 * to that account's owner and to the leads of <em>their</em> company. Addressing by the actor put
 * an administrator's work — and an administrator belongs to no company — into every lead's inbox
 * in both companies at once.
 */
@Service
public class AuditNotifier {

    private static final Logger log = LoggerFactory.getLogger(AuditNotifier.class);

    /** The name of this generator's row in {@code sync_state}. */
    private static final String CURSOR = "audit";

    /**
     * What each notified action reads like to a human.
     *
     * <p>This map is also the allowlist: an action that is not a key here produces no notification
     * at all. Left out on purpose:
     *
     * <ul>
     *   <li>{@code auth.login} — every sign-in, every day, by everybody. Pure noise.</li>
     *   <li>{@code user.changePassword} — you did it yourself a second ago.</li>
     *   <li>{@code user.update} — routine edits to a phone number or a spelling.</li>
     * </ul>
     *
     * <p>A failed or denied attempt at <em>any</em> action is still reported, whether or not it is
     * listed here — see {@link #worthNotifying}. Somebody being refused is worth knowing about
     * even when succeeding at it would not have been.
     */
    private static final Map<String, String> NOTIFIED = Map.of(
            "user.create", "created the account",
            "user.delete", "deleted the account",
            "user.assignRoles", "changed the roles on",
            "user.resetPassword", "reset the password for",
            "user.unlock", "unlocked the account",
            "role.create", "created the role",
            "role.update", "changed the role",
            "role.delete", "deleted the role",
            "performance.updateStandard", "changed the activity standard");

    /**
     * Actions the affected person is told about directly, not only their lead.
     *
     * <p>Each of these changes what somebody can do or how they sign in. Being told that your
     * roles moved or your password was reset is the moment you would notice if it was not you who
     * asked for it.
     */
    private static final Set<String> CONCERNS_THE_SUBJECT = Set.of(
            "user.assignRoles", "user.resetPassword", "user.unlock");

    /** Actions about the organisation rather than about one person. */
    private static final Set<String> ORGANISATION_WIDE = Set.of(
            "role.create", "role.update", "role.delete", "performance.updateStandard");

    private final UserServiceGrpc.UserServiceBlockingStub userService;
    private final Directory directory;
    private final Notifications notifications;
    private final SyncState syncState;
    private final NotificationProperties properties;

    public AuditNotifier(UserServiceGrpc.UserServiceBlockingStub userService,
                         Directory directory,
                         Notifications notifications,
                         SyncState syncState,
                         NotificationProperties properties) {
        this.userService = userService;
        this.directory = directory;
        this.notifications = notifications;
        this.syncState = syncState;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${drenix.notification.audit-initial-delay:PT30S}",
               fixedDelayString = "${drenix.notification.audit-interval:PT60S}")
    public void poll() {
        try {
            run();
        } catch (RuntimeException e) {
            // A stack trace a minute while user-service is restarting is noise, and an exception
            // escaping here would take the schedule down with it. Log the reason and try again.
            log.warn("Audit poll failed, will retry: {}", e.toString());
        }
    }

    /** Visible for a manual run. Returns how many notification rows were written. */
    public int run() {
        long lastSeen = syncState.readLong(CURSOR, 0L);
        List<AuditEntry> fresh = entriesAfter(lastSeen);
        if (fresh.isEmpty()) {
            return 0;
        }

        List<User> everyone = directory.everyone();
        Map<String, User> byId = new HashMap<>();
        everyone.forEach(u -> byId.put(u.getId(), u));

        // Grouped by recipient, so each person's batch is one question to the database rather
        // than one question per audit entry.
        Map<UUID, List<Notifications.Draft>> perRecipient = new LinkedHashMap<>();
        for (AuditEntry entry : fresh) {
            if (!worthNotifying(entry)) {
                continue;
            }
            User actor = byId.get(entry.getActorId());
            User subject = subjectOf(entry, byId, actor);
            Notifications.Draft draft = draft(entry, actor, subject);

            for (User recipient : recipients(entry, actor, subject, everyone)) {
                perRecipient.computeIfAbsent(UUID.fromString(recipient.getId()),
                        k -> new ArrayList<>()).add(draft);
            }
        }

        int written = 0;
        for (Map.Entry<UUID, List<Notifications.Draft>> addressed : perRecipient.entrySet()) {
            written += notifications.deliverBatch(addressed.getValue(), addressed.getKey());
        }

        long highest = fresh.stream().mapToLong(AuditEntry::getId).max().orElse(lastSeen);
        // Advanced only after the write. A crash in between replays the batch, and the dedupe key
        // makes that harmless — which is the whole reason the key exists.
        syncState.write(CURSOR, Long.toString(highest));
        log.info("Audit poll: {} entries, {} notifications, cursor now {}",
                fresh.size(), written, highest);
        return written;
    }

    /**
     * A listed action — whether it succeeded or was refused.
     *
     * <p>It used to be "a listed action, <em>or</em> anything that failed", on the reasoning that
     * being refused is worth knowing about even when succeeding would not have been. In practice
     * the only thing that ever fails is somebody mistyping their password: sixty notifications
     * were written about failed sign-ins for a username that does not exist, one to each of six
     * people, from a single afternoon of testing. Anybody who can reach the login form can
     * therefore fill every lead's inbox, and an inbox full of other people's typing is an inbox
     * nobody reads — which is exactly the failure the allowlist exists to prevent.
     *
     * <p>A refused {@code user.delete} still notifies, because {@code user.delete} is listed.
     * Failed sign-ins live in the audit log, and a repeated one locks the account, which is
     * visible on the account itself.
     */
    private static boolean worthNotifying(AuditEntry entry) {
        return NOTIFIED.containsKey(entry.getAction());
    }

    private static boolean failed(AuditEntry entry) {
        return !"SUCCESS".equals(entry.getOutcome());
    }

    /**
     * Who the notification is <em>about</em>.
     *
     * <p>The account that was acted on, when there is one. It decides both the wording and, more
     * importantly, whose company the notification belongs to: an administrator creating a BP
     * recruiter is BP's news, not JM's.
     */
    private static User subjectOf(AuditEntry entry, Map<String, User> byId, User actor) {
        if ("user".equals(entry.getTargetType()) && !entry.getTargetId().isBlank()) {
            // Null, not the actor, when the account cannot be resolved. Falling back to the actor
            // produced "IT Department deleted the account IT Department" for every deletion — a
            // deleted account is gone from the directory by the time this runs, so the fallback
            // fired every single time and named the wrong person in both the sentence and the
            // routing. A subject of null routes to the people who see the whole organisation,
            // which is the honest answer: the company of an account that no longer exists cannot
            // be looked up, and guessing it from the actor is what used to put an administrator's
            // work into both companies' inboxes at once.
            return byId.get(entry.getTargetId());
        }
        return actor;
    }

    /**
     * Who hears about it.
     *
     * <p>Three groups, and nobody else:
     *
     * <ol>
     *   <li>the person whose account was affected, for actions that change what they can do;</li>
     *   <li>the leads of <em>that person's</em> company;</li>
     *   <li>everyone who sees the whole organisation, for org-wide changes and for refusals.</li>
     * </ol>
     *
     * <p>The actor is always removed: nobody needs telling what they themselves just did.
     */
    private List<User> recipients(AuditEntry entry, User actor, User subject, List<User> everyone) {
        Set<User> recipients = new LinkedHashSet<>();

        if (ORGANISATION_WIDE.contains(entry.getAction())) {
            // Roles and the activity standard belong to no single company, so they go to the
            // people whose remit is the whole organisation and to nobody else.
            everyone.stream().filter(Directory::seesEverything).forEach(recipients::add);
        } else if (subject != null) {
            recipients.addAll(directory.supervisorsOf(subject, everyone));
            if (CONCERNS_THE_SUBJECT.contains(entry.getAction())) {
                recipients.add(subject);
            }
        } else {
            // An actor this service cannot identify — a failed login with an unknown username, or
            // an account since deleted. Only the people who see everything can act on that.
            everyone.stream().filter(Directory::seesEverything).forEach(recipients::add);
        }

        if (actor != null) {
            recipients.removeIf(r -> r.getId().equals(actor.getId()));
        }
        return List.copyOf(recipients);
    }

    /**
     * Everything newer than {@code lastSeen}, oldest first.
     *
     * <p>The audit list is newest-first and paged by "the id of the last row you saw", so this
     * walks back from the top and stops at the watermark.
     *
     * <p>A first run does not replay history. It takes the watermark from the newest page and
     * notifies about none of it: telling somebody about every action since the system was
     * installed helps nobody, and it is the audit log they would go to for that anyway.
     */
    private List<AuditEntry> entriesAfter(long lastSeen) {
        List<AuditEntry> collected = new ArrayList<>();
        String cursor = "";
        int limit = properties.getAuditBatch();

        for (int page = 0; page < 20; page++) {
            var response = userService.listAudit(ListAuditRequest.newBuilder()
                    .setPage(PageRequest.newBuilder().setLimit(limit).setCursor(cursor).build())
                    .build());

            boolean reachedWatermark = false;
            for (AuditEntry entry : response.getEntriesList()) {
                if (entry.getId() <= lastSeen) {
                    reachedWatermark = true;
                    break;
                }
                collected.add(entry);
            }
            cursor = response.getPage().getNextCursor();
            if (reachedWatermark || !response.getPage().getHasMore() || cursor.isBlank()
                || collected.size() >= limit) {
                break;
            }
        }

        if (lastSeen == 0L) {
            long highest = collected.stream().mapToLong(AuditEntry::getId).max().orElse(0L);
            syncState.write(CURSOR, Long.toString(highest));
            log.info("First audit poll: starting at id {} without replaying history", highest);
            return List.of();
        }

        collected.sort(Comparator.comparingLong(AuditEntry::getId));
        return collected;
    }

    private static Notifications.Draft draft(AuditEntry entry, User actor, User subject) {
        String actorName = actor != null
                ? Directory.displayName(actor)
                : (entry.getActorUsername().isBlank() ? "Somebody" : entry.getActorUsername());

        String verb = NOTIFIED.getOrDefault(entry.getAction(), entry.getAction());
        String target = targetName(entry, subject, actor);
        String phrase = verb + (target.isEmpty() ? "" : " " + target);

        boolean refused = failed(entry);
        String title = refused
                ? actorName + " was refused: " + phrase
                : actorName + " " + phrase;

        StringBuilder body = new StringBuilder(entry.getAction());
        if (refused) {
            body.append(" — ").append(entry.getOutcome().toLowerCase(Locale.ROOT));
        }
        if (!entry.getClientIp().isBlank()) {
            body.append(" from ").append(entry.getClientIp());
        }

        return new Notifications.Draft(
                Notifications.Kind.ACTIVITY,
                // A refusal is worth looking at; a successful change is a record.
                refused ? Notifications.Severity.ALERT : Notifications.Severity.INFO,
                title,
                body.toString(),
                subject == null ? null : UUID.fromString(subject.getId()),
                // The same trap as the title: falling back to the actor labels a deletion with
                // the name of the person who performed it. The recorded username is what the
                // notification is about; the actor is only who did it.
                subject == null ? (target.isEmpty() ? actorName : target)
                        : Directory.displayName(subject),
                Instant.ofEpochSecond(entry.getOccurredAt().getSeconds()),
                entry.getAction(),
                "audit:" + entry.getId());
    }

    /** What the action was done to, named rather than shown as a UUID where possible. */
    private static String targetName(AuditEntry entry, User subject, User actor) {
        if (entry.getTargetId().isBlank()) {
            return "";
        }
        if ("user".equals(entry.getTargetType())) {
            if (entry.getTargetId().equals(entry.getActorId())) {
                // A reflexive action. "Alex unlocked the account Alex" reads as a mistake.
                return "";
            }
            if (subject == null) {
                // Gone from the directory, which is the normal case for a deletion. The audit
                // entry recorded who it was at the time, and that name is the entire point of the
                // sentence — without it the reader is told an account was deleted and not which.
                return usernameFromDetail(entry);
            }
            return Directory.displayName(subject);
        }
        // A role carries a readable id already — its code. Anything else is an internal id nobody
        // would recognise, so it is left out of the sentence.
        return "role".equals(entry.getTargetType()) ? entry.getTargetId() : "";
    }

    /**
     * The username the audit entry recorded, or an empty string.
     *
     * <p>Read with a small regex rather than a JSON parser. The field is written by this system,
     * one level deep and always a string; pulling in a parser and a failure mode for it would be
     * more machinery than the one value is worth, and a miss degrades to the sentence simply not
     * naming the account.
     */
    private static String usernameFromDetail(AuditEntry entry) {
        java.util.regex.Matcher matcher = DETAIL_USERNAME.matcher(entry.getDetailJson());
        return matcher.find() ? matcher.group(1) : "";
    }

    private static final java.util.regex.Pattern DETAIL_USERNAME =
            java.util.regex.Pattern.compile("\"username\"\s*:\s*\"([^\"]*)\"");
}
