package uz.drenix.identity.user.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description = "";

    /** System roles are seeded by migration and cannot be deleted or renamed away. */
    @Column(name = "is_system", nullable = false)
    private boolean system = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_code"))
    @Column(name = "permission_code", nullable = false)
    private Set<String> permissions = new LinkedHashSet<>();

    protected RoleEntity() {
    }

    public RoleEntity(String code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description == null ? "" : description;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isSystem() { return system; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Set<String> getPermissions() { return permissions; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description == null ? "" : description; }

    public void replacePermissions(Set<String> next) {
        this.permissions.clear();
        this.permissions.addAll(next);
        this.updatedAt = Instant.now();
    }
}
