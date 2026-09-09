package uz.drenix.identity.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * How far a generator has got.
 *
 * <p>In the database rather than in memory: without it the audit poller replays the whole log
 * after every restart and everybody's inbox fills with things they were told about last week.
 */
@Entity
@Table(name = "sync_state")
public class SyncStateEntity {

    @Id
    private String name;

    @Column(nullable = false)
    private String position;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SyncStateEntity() {
    }

    public SyncStateEntity(String name, String position) {
        this.name = name;
        this.position = position;
    }

    public String getName() { return name; }
    public String getPosition() { return position; }
    public void setPosition(String position) {
        this.position = position;
        this.updatedAt = Instant.now();
    }
    public Instant getUpdatedAt() { return updatedAt; }
}
