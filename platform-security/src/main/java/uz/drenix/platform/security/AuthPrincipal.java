package uz.drenix.platform.security;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, as reconstructed from a verified access token.
 *
 * <p>Permissions are carried in the token rather than looked up per request. That keeps every
 * service independent of user-service on the hot path, at the cost of a stale window bounded by
 * the access token lifetime (10 minutes). Revocation that must take effect sooner goes through
 * the session denylist, which the gateway checks on every request.
 */
public record AuthPrincipal(
        UUID userId,
        String username,
        String sessionId,
        String tokenId,
        Set<String> permissions,
        Set<EntityScope> entityScopes) {

    /**
     * A role assignment narrowed to one company, or {@link EntityScope#GLOBAL} for all of them.
     * Only the companies that actually operate are listed; see Entity in common.proto.
     */
    public enum EntityScope {
        GLOBAL, JM, BP
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    /**
     * True when the caller may act on the given company. A global assignment covers everything;
     * anything else must match exactly. Callers that forget to check this can read across
     * companies, so every query that touches entity-owned data must pass through here.
     */
    public boolean canActOn(EntityScope entity) {
        return entityScopes.contains(EntityScope.GLOBAL) || entityScopes.contains(entity);
    }
}
