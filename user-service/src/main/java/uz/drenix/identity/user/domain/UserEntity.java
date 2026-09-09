package uz.drenix.identity.user.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    /**
     * Argon2id encoding. Never mapped into a DTO, never logged, never returned over gRPC —
     * VerifyCredentials takes the candidate password and answers yes/no instead.
     */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "password_updated_at", nullable = false)
    private Instant passwordUpdatedAt = Instant.now();

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = true;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String email;
    private String phone;

    /**
     * The RingCentral extensions or numbers this person's calls and messages are logged against.
     * Separate from {@link #phone}: that one is how a colleague reaches them, these are how the
     * call metrics find them.
     *
     * <p>A list because leads work more than one extension, and the report has to add them up.
     * Order is the order an admin entered them in; the first is the one shown wherever a single
     * number is displayed.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_ring_central_phones",
                     joinColumns = @JoinColumn(name = "user_id"))
    @OrderColumn(name = "position")
    @Column(name = "phone", nullable = false)
    private List<String> ringCentralPhones = new ArrayList<>();

    /**
     * What the monday.com board's Source column calls this person. The recruiting chart is keyed
     * on it; without it there is no way to tell which account a board row belongs to.
     */
    @Column(name = "monday_name")
    private String mondayName;

    /**
     * The day this person started with the company.
     *
     * <p>Null for most rows, and that is the normal state rather than a defect: it is a fact only
     * a human knows, and the recruiting chart falls back to inferring tenure from their first hire
     * on the board. The inference can only ever be later than the truth — it cannot see a month in
     * which somebody made no hires, nor anything before the board existed — so where this is
     * filled in, it wins.
     */
    @Column(name = "employment_start_date")
    private java.time.LocalDate employmentStartDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "failed_attempts", nullable = false)
    private short failedAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "created_by")
    private UUID createdBy;

    /** Soft delete: the row stays for the audit trail, the username is freed by a partial index. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<UserRoleEntity> roles = new LinkedHashSet<>();

    protected UserEntity() {
    }

    public UserEntity(UUID id, String username, String passwordHash, String fullName, UUID createdBy) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.createdBy = createdBy;
    }

    /** True while a lock window is still open. Expired locks are treated as cleared. */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getPasswordUpdatedAt() { return passwordUpdatedAt; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public List<String> getRingCentralPhones() { return ringCentralPhones; }
    public String getMondayName() { return mondayName; }
    public java.time.LocalDate getEmploymentStartDate() { return employmentStartDate; }
    public UserStatus getStatus() { return status; }
    public short getFailedAttempts() { return failedAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getDeletedAt() { return deletedAt; }
    public Set<UserRoleEntity> getRoles() { return roles; }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.passwordUpdatedAt = Instant.now();
    }

    public void setMustChangePassword(boolean value) { this.mustChangePassword = value; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setEmail(String email) { this.email = email; }
    public void setPhone(String phone) { this.phone = phone; }
    /** Replaces the list in place, so Hibernate keeps tracking the same collection. */
    public void setRingCentralPhones(List<String> phones) {
        this.ringCentralPhones.clear();
        if (phones != null) {
            this.ringCentralPhones.addAll(phones);
        }
    }
    public void setMondayName(String v) { this.mondayName = v; }
    public void setEmploymentStartDate(java.time.LocalDate v) { this.employmentStartDate = v; }
    public void setStatus(UserStatus status) { this.status = status; }
    public void setFailedAttempts(short failedAttempts) { this.failedAttempts = failedAttempts; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
