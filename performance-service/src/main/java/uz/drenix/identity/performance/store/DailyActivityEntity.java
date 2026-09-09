package uz.drenix.identity.performance.store;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** One person's counts for one shift. */
@Entity
@Table(name = "daily_activity")
public class DailyActivityEntity {

    /** A person and a shift date. Natural, and it makes the upsert a plain save. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(name = "shift_date", nullable = false)
        private LocalDate shiftDate;

        protected Key() {
        }

        public Key(UUID userId, LocalDate shiftDate) {
            this.userId = userId;
            this.shiftDate = shiftDate;
        }

        public UUID getUserId() { return userId; }
        public LocalDate getShiftDate() { return shiftDate; }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                   && Objects.equals(userId, key.userId)
                   && Objects.equals(shiftDate, key.shiftDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, shiftDate);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(nullable = false)
    private String entity;

    @Column(nullable = false)
    private int calls;

    @Column(name = "talk_seconds", nullable = false)
    private int talkSeconds;

    @Column(name = "sms_sent", nullable = false)
    private int smsSent;

    @Column(nullable = false)
    private String phones = "";

    /** True once the shift has ended and these counts can no longer change. */
    @Column(name = "final", nullable = false)
    private boolean shiftFinished;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt = Instant.now();

    protected DailyActivityEntity() {
    }

    public DailyActivityEntity(Key id, String entity) {
        this.id = id;
        this.entity = entity;
    }

    public Key getId() { return id; }
    public String getEntity() { return entity; }
    public void setEntity(String entity) { this.entity = entity; }
    public int getCalls() { return calls; }
    public void setCalls(int calls) { this.calls = calls; }
    public int getTalkSeconds() { return talkSeconds; }
    public void setTalkSeconds(int talkSeconds) { this.talkSeconds = talkSeconds; }
    public int getSmsSent() { return smsSent; }
    public void setSmsSent(int smsSent) { this.smsSent = smsSent; }
    public String getPhones() { return phones; }
    public void setPhones(String phones) { this.phones = phones; }
    public boolean isShiftFinished() { return shiftFinished; }
    public void setShiftFinished(boolean shiftFinished) { this.shiftFinished = shiftFinished; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}
