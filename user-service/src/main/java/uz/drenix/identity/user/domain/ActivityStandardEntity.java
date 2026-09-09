package uz.drenix.identity.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The one row of {@code activity_standard}.
 *
 * <p>There is exactly one, and the database enforces it with a {@code CHECK (id = 1)}. The id is
 * therefore never chosen by anybody — {@link #SINGLETON_ID} is the only value that exists.
 */
@Entity
@Table(name = "activity_standard")
public class ActivityStandardEntity {

    public static final short SINGLETON_ID = 1;

    @Id
    private Short id = SINGLETON_ID;

    @Column(name = "calls_per_day", nullable = false)
    private int callsPerDay;

    @Column(name = "talk_seconds_per_day", nullable = false)
    private int talkSecondsPerDay;

    @Column(name = "sms_sent_per_day", nullable = false)
    private int smsSentPerDay;

    @Column(name = "shift_minutes", nullable = false)
    private int shiftMinutes;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    public Short getId() { return id; }
    public void setId(Short id) { this.id = id; }
    public int getCallsPerDay() { return callsPerDay; }
    public void setCallsPerDay(int callsPerDay) { this.callsPerDay = callsPerDay; }
    public int getTalkSecondsPerDay() { return talkSecondsPerDay; }
    public void setTalkSecondsPerDay(int v) { this.talkSecondsPerDay = v; }
    public int getSmsSentPerDay() { return smsSentPerDay; }
    public void setSmsSentPerDay(int smsSentPerDay) { this.smsSentPerDay = smsSentPerDay; }
    public int getShiftMinutes() { return shiftMinutes; }
    public void setShiftMinutes(int shiftMinutes) { this.shiftMinutes = shiftMinutes; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
