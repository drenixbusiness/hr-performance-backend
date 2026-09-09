package uz.drenix.identity.user.service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.drenix.identity.user.domain.Entity4;
import uz.drenix.identity.user.domain.UserEntity;
import uz.drenix.identity.user.domain.UserRoleEntity;
import uz.drenix.identity.user.domain.UserStatus;
import uz.drenix.identity.user.repo.RoleRepository;
import uz.drenix.identity.user.repo.UserRepository;
import uz.drenix.platform.security.AlreadyExistsException;
import uz.drenix.platform.security.AuthPrincipal;

/**
 * Admin-side user lifecycle. There is no self-registration anywhere in the system: an account
 * exists because a named administrator created it, and the audit log says who and when.
 */
@Service
public class UserAdminService {

    public record RoleGrant(String roleCode, Entity4 entity) {
    }

    public record Page(List<UserEntity> users, String nextCursor, boolean hasMore) {
    }

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    /** Matches no row, so an existence check on create excludes nothing. */
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final AuditService audit;

    public UserAdminService(UserRepository users, RoleRepository roles, PasswordHasher hasher,
                            PasswordPolicy policy, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.hasher = hasher;
        this.policy = policy;
        this.audit = audit;
    }

    @Transactional
    public UserEntity create(AuthPrincipal actor, String username, String fullName, String email,
                             String phone, List<String> ringCentralPhones, String mondayName,
                             String employmentStartDate,
                             String initialPassword,
                             List<RoleGrant> grants,
                             String clientIp, String correlationId) {

        String normalisedUsername = username == null ? "" : username.trim().toLowerCase();
        if (users.existsByUsernameIgnoreCaseAndDeletedAtIsNull(normalisedUsername)) {
            throw new AlreadyExistsException("Username already taken");
        }
        List<String> rcPhones = cleanPhones(ringCentralPhones);
        // NIL_UUID excludes nobody: on create there is no own record to skip.
        requireUnclaimed(rcPhones, NIL_UUID);
        String monday = blankToNull(mondayName);
        if (monday != null && users.existsByMondayNameIgnoreCaseAndDeletedAtIsNull(monday)) {
            throw new AlreadyExistsException("That monday.com name belongs to another account");
        }
        policy.validate(initialPassword, normalisedUsername, fullName);
        validateGrants(grants);

        UserEntity user = new UserEntity(
                UUID.randomUUID(), normalisedUsername, hasher.hash(initialPassword), fullName, actor.userId());
        user.setEmail(blankToNull(email));
        user.setPhone(blankToNull(phone));
        user.setRingCentralPhones(rcPhones);
        user.setMondayName(monday);
        user.setEmploymentStartDate(parseDate(employmentStartDate));
        // Always true on creation: the admin who typed the password must not keep the ability
        // to sign in as this person.
        user.setMustChangePassword(true);
        applyGrants(user, grants, actor.userId());

        UserEntity saved = users.save(user);
        audit.record(actor.userId(), actor.username(), "user.create", "user", saved.getId().toString(),
                AuditService.Outcome.SUCCESS, clientIp, null, correlationId,
                Map.of("username", normalisedUsername, "roles", describeGrants(grants)));
        return saved;
    }

    @Transactional(readOnly = true)
    public UserEntity get(UUID id) {
        return users.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NoSuchElementException("User not found"));
    }

    /** Sentinels for "no cursor yet": every real row sorts before these. */
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");
    private static final UUID MAX_UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Transactional(readOnly = true)
    public Page list(String search, String cursor, int limit) {
        int size = limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        Cursor decoded = Cursor.decode(cursor);
        String pattern = (search == null || search.isBlank())
                ? "%"
                : "%" + search.trim().toLowerCase() + "%";

        // Fetch one extra row to learn whether another page exists without a second count query.
        List<UserEntity> rows = users.findPage(pattern, decoded.createdAt(), decoded.id(), Limit.of(size + 1));

        boolean hasMore = rows.size() > size;
        List<UserEntity> page = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore
                ? Cursor.encode(page.getLast().getCreatedAt(), page.getLast().getId())
                : "";
        return new Page(page, next, hasMore);
    }

    @Transactional
    public UserEntity update(AuthPrincipal actor, UUID id, Set<String> mask, String fullName,
                             String email, String phone, List<String> ringCentralPhones,
                             String mondayName,
                             String employmentStartDate,
                             UserStatus status, String clientIp, String correlationId) {
        UserEntity user = get(id);

        if (mask.contains("full_name")) user.setFullName(fullName);
        if (mask.contains("email")) user.setEmail(blankToNull(email));
        if (mask.contains("phone")) user.setPhone(blankToNull(phone));
        if (mask.contains("ring_central_phones")) {
            List<String> rcPhones = cleanPhones(ringCentralPhones);
            requireUnclaimed(rcPhones, id);
            user.setRingCentralPhones(rcPhones);
        }
        if (mask.contains("monday_name")) {
            String monday = blankToNull(mondayName);
            if (monday != null
                    && users.existsByMondayNameIgnoreCaseAndDeletedAtIsNullAndIdNot(monday, id)) {
                throw new AlreadyExistsException("That monday.com name belongs to another account");
            }
            user.setMondayName(monday);
        }
        if (mask.contains("employment_start_date")) {
            // An empty string clears it, which is the only way back to the inferred tenure once
            // somebody has entered a wrong date.
            user.setEmploymentStartDate(parseDate(employmentStartDate));
        }
        if (mask.contains("status")) {
            guardSelfLockout(actor, user, status);
            user.setStatus(status);
            if (status == UserStatus.ACTIVE) {
                user.setLockedUntil(null);
                user.setFailedAttempts((short) 0);
            }
        }
        user.touch();

        audit.record(actor.userId(), actor.username(), "user.update", "user", id.toString(),
                AuditService.Outcome.SUCCESS, clientIp, null, correlationId, Map.of("fields", mask));
        return user;
    }

    @Transactional
    public void softDelete(AuthPrincipal actor, UUID id, String reason, String clientIp, String correlationId) {
        if (actor.userId().equals(id)) {
            throw new IllegalArgumentException("You cannot delete your own account");
        }
        UserEntity user = get(id);
        user.setDeletedAt(Instant.now());
        user.setStatus(UserStatus.DISABLED);
        // Release the RingCentral numbers so they can be given to whoever takes over the desk.
        // The old single-column unique index skipped deleted rows to the same effect; the child
        // table cannot express that, so the rows go instead.
        user.setRingCentralPhones(List.of());
        user.touch();

        audit.record(actor.userId(), actor.username(), "user.delete", "user", id.toString(),
                AuditService.Outcome.SUCCESS, clientIp, null, correlationId,
                Map.of("username", user.getUsername(), "reason", reason == null ? "" : reason));
    }

    @Transactional
    public UserEntity assignRoles(AuthPrincipal actor, UUID id, List<RoleGrant> grants,
                                  String clientIp, String correlationId) {
        validateGrants(grants);
        UserEntity user = get(id);
        guardSelfDemotion(actor, user, grants);

        // Removing everything and re-adding does not work here. Hibernate orders the INSERTs
        // before the DELETEs within one flush, so re-granting a role the user already holds
        // collides with user_roles_unique_idx and the whole call fails — which made "keep
        // SUPER_ADMIN and add HR_LEAD" impossible while a disjoint set worked fine.
        //
        // Diffing avoids the ordering question entirely, and it leaves granted_at and granted_by
        // untouched on the grants that are not changing, so the audit trail keeps saying when each
        // role was actually given rather than when the list was last saved.
        Set<RoleGrant> wanted = new LinkedHashSet<>(grants);
        user.getRoles().removeIf(held ->
                !wanted.contains(new RoleGrant(held.getRoleCode(), held.getEntity())));

        Set<RoleGrant> alreadyHeld = user.getRoles().stream()
                .map(held -> new RoleGrant(held.getRoleCode(), held.getEntity()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (RoleGrant grant : wanted) {
            if (!alreadyHeld.contains(grant)) {
                user.getRoles().add(new UserRoleEntity(user, grant.roleCode(), grant.entity(),
                        actor.userId()));
            }
        }
        user.touch();

        audit.record(actor.userId(), actor.username(), "user.assignRoles", "user", id.toString(),
                AuditService.Outcome.SUCCESS, clientIp, null, correlationId,
                Map.of("roles", describeGrants(grants)));
        return user;
    }

    @Transactional
    public void resetPassword(AuthPrincipal actor, UUID id, String newPassword,
                              String clientIp, String correlationId) {
        UserEntity user = get(id);
        policy.validate(newPassword, user.getUsername(), user.getFullName());

        user.setPasswordHash(hasher.hash(newPassword));
        user.setMustChangePassword(true);   // admin-set passwords are always one-shot
        user.setFailedAttempts((short) 0);
        user.setLockedUntil(null);
        if (user.getStatus() == UserStatus.LOCKED) {
            user.setStatus(UserStatus.ACTIVE);
        }
        user.touch();

        audit.record(actor.userId(), actor.username(), "user.resetPassword", "user", id.toString(),
                AuditService.Outcome.SUCCESS, clientIp, null, correlationId, Map.of());
    }

    /**
     * Clears a lockout without touching the password.
     *
     * <p>Separate from {@link #resetPassword} on purpose. A lockout is the system defending itself
     * against guessing; once an administrator has established that the person at the keyboard is
     * who they say they are, making them also pick a new password punishes the victim of the
     * guessing rather than the guesser.
     *
     * <p>Recorded even when the account was not locked. "Someone asked me to unlock this account"
     * is worth knowing whether or not the lock was still in force.
     */
    @Transactional
    public UserEntity unlock(AuthPrincipal actor, UUID id, String clientIp, String correlationId) {
        UserEntity user = get(id);
        boolean wasLocked = user.getStatus() == UserStatus.LOCKED
                            || user.getLockedUntil() != null
                            || user.getFailedAttempts() > 0;

        user.setFailedAttempts((short) 0);
        user.setLockedUntil(null);
        if (user.getStatus() == UserStatus.LOCKED) {
            // Only LOCKED is undone here. A DISABLED account was switched off by a person, and
            // unlocking must not quietly re-enable it.
            user.setStatus(UserStatus.ACTIVE);
        }
        user.touch();

        audit.record(actor.userId(), actor.username(), "user.unlock", "user", id.toString(),
                AuditService.Outcome.SUCCESS, clientIp, null, correlationId,
                Map.of("wasLocked", wasLocked));
        return user;
    }

    @Transactional
    public void changeOwnPassword(UUID userId, String currentPassword, String newPassword,
                                  String clientIp, String correlationId) {
        UserEntity user = get(userId);

        if (!hasher.matches(currentPassword, user.getPasswordHash())) {
            audit.record(userId, user.getUsername(), "user.changePassword", "user", userId.toString(),
                    AuditService.Outcome.FAILURE, clientIp, null, correlationId,
                    Map.of("reason", "current password mismatch"));
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (currentPassword.equals(newPassword)) {
            throw new PasswordPolicy.PolicyViolationException("New password must differ from the current one");
        }
        policy.validate(newPassword, user.getUsername(), user.getFullName());

        user.setPasswordHash(hasher.hash(newPassword));
        user.setMustChangePassword(false);
        user.touch();

        audit.record(userId, user.getUsername(), "user.changePassword", "user", userId.toString(),
                AuditService.Outcome.SUCCESS, clientIp, null, correlationId, Map.of());
    }

    /** Flattened permission set for a user, used when auth-service mints a token. */
    @Transactional(readOnly = true)
    public Set<String> permissionsOf(UserEntity user) {
        Set<String> roleCodes = new LinkedHashSet<>();
        user.getRoles().forEach(r -> roleCodes.add(r.getRoleCode()));
        return roleCodes.isEmpty() ? Set.of() : roles.findPermissionCodes(roleCodes);
    }

    // -----------------------------------------------------------------------

    private void applyGrants(UserEntity user, List<RoleGrant> grants, UUID grantedBy) {
        for (RoleGrant grant : grants) {
            user.getRoles().add(new UserRoleEntity(user, grant.roleCode(), grant.entity(), grantedBy));
        }
    }

    private void validateGrants(List<RoleGrant> grants) {
        if (grants == null || grants.isEmpty()) {
            return;   // an account with no role can sign in and do nothing, which is a valid state
        }
        Set<String> known = new LinkedHashSet<>();
        roles.findAllByOrderByCodeAsc().forEach(r -> known.add(r.getCode()));
        for (RoleGrant grant : grants) {
            if (!known.contains(grant.roleCode())) {
                throw new IllegalArgumentException("Unknown role: " + grant.roleCode());
            }
        }
    }

    /** Stops an admin from disabling themselves and locking everyone out of the console. */
    private void guardSelfLockout(AuthPrincipal actor, UserEntity target, UserStatus next) {
        if (actor.userId().equals(target.getId()) && next != UserStatus.ACTIVE) {
            throw new IllegalArgumentException("You cannot disable your own account");
        }
    }

    /**
     * The roles that can grant themselves back. Losing one by accident locks the system.
     *
     * <p>OWNER as well as SUPER_ADMIN: an owner who drops their own OWNER role has no way to
     * restore it without another owner or a database, and there may be neither.
     */
    private static final Set<String> IRREVOCABLE_BY_SELF = Set.of("SUPER_ADMIN", "OWNER");

    /** Same idea for roles: do not let somebody drop the role that lets them grant it back. */
    private void guardSelfDemotion(AuthPrincipal actor, UserEntity target, List<RoleGrant> next) {
        if (!actor.userId().equals(target.getId())) {
            return;
        }
        for (String role : IRREVOCABLE_BY_SELF) {
            boolean had = target.getRoles().stream().anyMatch(r -> role.equals(r.getRoleCode()));
            boolean keeps = next.stream().anyMatch(g -> role.equals(g.roleCode()));
            if (had && !keeps) {
                throw new IllegalArgumentException(
                        "You cannot remove " + role + " from your own account");
            }
        }
    }

    private static List<String> describeGrants(List<RoleGrant> grants) {
        return grants == null ? List.of() : grants.stream()
                .map(g -> g.roleCode() + "@" + (g.entity() == null ? "*" : g.entity().name()))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * A calendar date, or null for blank.
     *
     * @throws IllegalArgumentException for anything that is neither. Rejecting is the point: a
     *     silently ignored bad date would leave the account on inferred tenure while the person
     *     who typed it believes they have corrected it.
     */
    private static java.time.LocalDate parseDate(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(trimmed);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "employmentStartDate must look like 2026-06-01");
        }
    }

    /**
     * Trimmed, blanks dropped, duplicates dropped, order kept.
     *
     * <p>The same number twice would be counted twice by the activity report, so it is dropped
     * here rather than left for the unique index to reject: a repeat in a list of two is a slip,
     * not an attempt to claim somebody else's extension.
     */
    private static List<String> cleanPhones(List<String> phones) {
        if (phones == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String phone : phones) {
            String clean = blankToNull(phone);
            if (clean != null) {
                unique.add(clean);
            }
        }
        return List.copyOf(unique);
    }

    /** @throws AlreadyExistsException naming the first number another live account already holds. */
    private void requireUnclaimed(List<String> phones, UUID selfId) {
        if (phones.isEmpty()) {
            return;
        }
        List<String> taken = users.ringCentralPhonesTakenBySomeoneElse(phones, selfId);
        if (!taken.isEmpty()) {
            throw new AlreadyExistsException(
                    "RingCentral number " + taken.getFirst() + " belongs to another account");
        }
    }

    /** Opaque page cursor: base64 of "createdAtEpochMicros:uuid". */
    private record Cursor(Instant createdAt, UUID id) {

        static Cursor decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return new Cursor(FAR_FUTURE, MAX_UUID);
            }
            try {
                String raw = new String(java.util.Base64.getUrlDecoder().decode(encoded));
                int sep = raw.indexOf(':');
                long micros = Long.parseLong(raw.substring(0, sep));
                return new Cursor(
                        Instant.EPOCH.plusNanos(micros * 1_000L),
                        UUID.fromString(raw.substring(sep + 1)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Malformed cursor");
            }
        }

        static String encode(Instant createdAt, UUID id) {
            long micros = createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000L;
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((micros + ":" + id).getBytes());
        }
    }
}
