package uz.drenix.identity.notification.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.drenix.identity.notification.domain.SyncStateEntity;
import uz.drenix.identity.notification.repo.SyncStateRepository;

/** How far each generator has got, kept where a restart cannot lose it. */
@Service
public class SyncState {

    private final SyncStateRepository states;

    public SyncState(SyncStateRepository states) {
        this.states = states;
    }

    @Transactional(readOnly = true)
    public String read(String name, String fallback) {
        return states.findById(name).map(SyncStateEntity::getPosition).orElse(fallback);
    }

    public long readLong(String name, long fallback) {
        try {
            return Long.parseLong(read(name, Long.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Committed on its own, not with the batch that produced it.
     *
     * <p>REQUIRES_NEW so the watermark survives even if the caller's transaction later rolls back.
     * The alternative — a shared transaction — would move the watermark and the notifications
     * together, which sounds safer but means a partial failure loses both and replays everything.
     * Notifications are deduplicated by key, so replaying is free; skipping is not.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String name, String position) {
        SyncStateEntity state = states.findById(name).orElseGet(
                () -> new SyncStateEntity(name, position));
        state.setPosition(position);
        states.save(state);
    }
}
