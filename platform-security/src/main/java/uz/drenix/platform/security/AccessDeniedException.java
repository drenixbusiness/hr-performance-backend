package uz.drenix.platform.security;

/** Thrown when an authenticated caller lacks the permission a call requires. */
public class AccessDeniedException extends RuntimeException {

    private final String requiredPermission;

    public AccessDeniedException(String requiredPermission) {
        // The message deliberately omits the caller identity: it ends up in logs, and
        // pairing a username with a denied permission is a useful hint for an attacker.
        super("Missing permission: " + requiredPermission);
        this.requiredPermission = requiredPermission;
    }

    public String requiredPermission() {
        return requiredPermission;
    }
}
