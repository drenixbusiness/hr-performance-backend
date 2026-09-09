package uz.drenix.platform.security;

/**
 * Permission codes as constants, so a typo fails at compile time instead of silently
 * denying (or worse, silently allowing) at runtime. Must stay in sync with the
 * {@code permissions} table seeded by V2__seed_permissions_roles.sql.
 */
public final class Permissions {

    public static final String USER_CREATE = "user:create";
    public static final String USER_READ = "user:read";
    public static final String USER_UPDATE = "user:update";
    public static final String USER_DELETE = "user:delete";
    public static final String USER_ASSIGN_ROLE = "user:assignRole";
    public static final String USER_RESET_PASSWORD = "user:resetPassword";

    public static final String ROLE_CREATE = "role:create";
    public static final String ROLE_READ = "role:read";
    public static final String ROLE_UPDATE = "role:update";
    public static final String ROLE_DELETE = "role:delete";

    public static final String AUDIT_READ = "audit:read";
    public static final String SESSION_REVOKE = "session:revoke";
    public static final String AUTH_CHANGE_OWN_PASSWORD = "auth:changeOwnPassword";

    /** Reading the recruiting performance chart. */
    public static final String PERFORMANCE_READ = "performance:read";

    /** Everyone in the holder's own company. */
    public static final String PERFORMANCE_READ_TEAM = "performance:readTeam";

    /** Every recruiter, in every company. */
    public static final String PERFORMANCE_READ_ALL = "performance:readAll";

    /** Reading your own notifications. Held by every role; there is no inbox but your own. */
    public static final String NOTIFICATION_READ = "notification:read";

    /** Changing the daily call, talk and SMS targets everybody is judged against. */
    public static final String PERFORMANCE_MANAGE_STANDARD = "performance:manageStandard";

    private Permissions() {
    }
}
