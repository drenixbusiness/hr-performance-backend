package uz.drenix.identity.user.domain;

public enum UserStatus {
    /** Normal account. */
    ACTIVE,
    /** Switched off by an admin. Only an admin can bring it back. */
    DISABLED,
    /** Too many failed logins. Clears itself once the lock window expires. */
    LOCKED
}
