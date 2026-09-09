package uz.drenix.identity.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One role granted to one user, optionally narrowed to a single company.
 *
 * <p>{@code entity == null} means the grant applies everywhere. That is the shape the business
 * needs: a JM recruiter lead should see JM data only, while the operator sees all four.
 */
@Entity
@Table(name = "user_roles")
public class UserRoleEntity {

    @Id
    // IDENTITY, not AUTO: the column is BIGSERIAL, so Postgres owns the counter. AUTO would
    // look for a Hibernate-style sequence that does not exist and fail schema validation.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "role_code", nullable = false)
    private String roleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity")
    private Entity4 entity;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    @Column(name = "granted_by")
    private UUID grantedBy;

    protected UserRoleEntity() {
    }

    public UserRoleEntity(UserEntity user, String roleCode, Entity4 entity, UUID grantedBy) {
        this.user = user;
        this.roleCode = roleCode;
        this.entity = entity;
        this.grantedBy = grantedBy;
    }

    public Long getId() { return id; }
    public UserEntity getUser() { return user; }
    public String getRoleCode() { return roleCode; }
    public Entity4 getEntity() { return entity; }
    public Instant getGrantedAt() { return grantedAt; }
    public UUID getGrantedBy() { return grantedBy; }
}
