package uz.drenix.identity.notification.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.drenix.identity.notification.domain.NotificationEntity;
import uz.drenix.identity.notification.repo.NotificationRepository;

/**
 * Writing notifications, and the one rule that matters when doing it: never twice.
 *
 * <p>Both generators are allowed to run again over ground they have already covered — a restart
 * mid-batch, a schedule that overlaps, a manual re-run. Each notification therefore carries a
 * {@code dedupeKey} that identifies the thing being reported, and a second attempt at the same
 * key for the same person is dropped rather than written.
 */
@Service
public class Notifications {

    private static final Logger log = LoggerFactory.getLogger(Notifications.class);

    public enum Kind { ACTIVITY, PERFORMANCE }

    public enum Severity { INFO, WARNING, ALERT }

    /**
     * One notification, before it is addressed to anybody.
     *
     * @param dedupeKey identifies the event, not the recipient: the same event fanned out to five
     *                  leads uses one key five times, once per recipient row.
     */
    public record Draft(Kind kind, Severity severity, String title, String body,
                        UUID subjectId, String subjectName, Instant occurredAt,
                        String reference, String dedupeKey) {
    }

    private final NotificationRepository notifications;

    public Notifications(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /**
     * Delivers one draft to each recipient, skipping anybody who already has it.
     *
     * @return how many rows were actually written
     */
    @Transactional
    public int deliver(Draft draft, List<UUID> recipients) {
        List<NotificationEntity> rows = new ArrayList<>();
        for (UUID recipient : new java.util.LinkedHashSet<>(recipients)) {
            if (!notifications.existingKeys(recipient, List.of(draft.dedupeKey())).isEmpty()) {
                continue;
            }
            rows.add(toEntity(draft, recipient));
        }
        if (rows.isEmpty()) {
            return 0;
        }
        notifications.saveAll(rows);
        return rows.size();
    }

    /**
     * Delivers many drafts to one recipient in a single pass.
     *
     * <p>The audit poller produces a batch at a time and fans each entry out to the same handful
     * of leads. Asking the database once which of two hundred keys that person already has beats
     * two hundred single-key questions.
     */
    @Transactional
    public int deliverBatch(List<Draft> drafts, UUID recipient) {
        if (drafts.isEmpty()) {
            return 0;
        }
        Set<String> already = new HashSet<>(notifications.existingKeys(
                recipient, drafts.stream().map(Draft::dedupeKey).distinct().toList()));

        List<NotificationEntity> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Draft draft : drafts) {
            // `seen` guards against a duplicate key inside the batch itself, which the unique
            // index would otherwise reject and take the whole flush down with it.
            if (already.contains(draft.dedupeKey()) || !seen.add(draft.dedupeKey())) {
                continue;
            }
            rows.add(toEntity(draft, recipient));
        }
        if (rows.isEmpty()) {
            return 0;
        }
        notifications.saveAll(rows);
        log.debug("Wrote {} notifications for {}", rows.size(), recipient);
        return rows.size();
    }

    private static NotificationEntity toEntity(Draft draft, UUID recipient) {
        NotificationEntity entity = new NotificationEntity();
        entity.setRecipientId(recipient);
        entity.setKind(draft.kind().name());
        entity.setSeverity(draft.severity().name());
        entity.setTitle(draft.title());
        entity.setBody(draft.body());
        entity.setSubjectId(draft.subjectId());
        entity.setSubjectName(draft.subjectName());
        entity.setOccurredAt(draft.occurredAt());
        entity.setReference(draft.reference() == null ? "" : draft.reference());
        entity.setDedupeKey(draft.dedupeKey());
        return entity;
    }
}
