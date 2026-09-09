package uz.drenix.identity.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only from the application's point of view: rows come from migrations. */
@Entity
@Table(name = "permissions")
public class PermissionEntity {

    @Id
    private String code;

    @Column(nullable = false)
    private String resource;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String description = "";

    protected PermissionEntity() {
    }

    public String getCode() { return code; }
    public String getResource() { return resource; }
    public String getAction() { return action; }
    public String getDescription() { return description; }
}
