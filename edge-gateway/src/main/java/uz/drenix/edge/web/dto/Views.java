package uz.drenix.edge.web.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import uz.drenix.identity.grpc.v1.AuditEntry;
import uz.drenix.identity.grpc.v1.Entity;
import uz.drenix.identity.grpc.v1.ListAuditResponse;
import uz.drenix.identity.grpc.v1.ListSessionsResponse;
import uz.drenix.identity.grpc.v1.SessionInfo;
import uz.drenix.identity.grpc.v1.ListPermissionsResponse;
import uz.drenix.identity.grpc.v1.ListRolesResponse;
import uz.drenix.identity.grpc.v1.ListUsersResponse;
import uz.drenix.identity.grpc.v1.Permission;
import uz.drenix.identity.grpc.v1.Role;
import uz.drenix.identity.grpc.v1.RoleAssignment;
import uz.drenix.identity.grpc.v1.User;

/**
 * Protobuf messages to plain records.
 *
 * <p>Protobuf types are never returned from a controller directly: they carry descriptors,
 * unknown-field sets and builder plumbing that a JSON mapper either chokes on or renders as
 * noise. Mapping also means the wire format of the internal API can change without silently
 * changing the public one.
 */
public final class Views {

    private Views() {
    }

    @Schema(description = "A role held by a user, and the company it applies to.")
    public record RoleAssignmentView(
            @Schema(example = "AUDITOR") String roleCode,
            @Schema(description = "The company this grant is limited to, or null when it covers "
                                  + "every company.",
                    example = "JM") String entity) {
    }

    @Schema(description = "A user account.")
    public record UserView(
            @Schema(description = "Stable identifier. Use it in the other admin endpoints.",
                    example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338") String id,
            @Schema(example = "j.karimov") String username,
            @Schema(example = "Jasur Karimov") String fullName,
            @Schema(description = "Null when not set.", example = "j.karimov@drenix.uz") String email,
            @Schema(description = "Null when not set.", example = "+998901234567") String phone,
            @Schema(description = "The first RingCentral number, or null. Read `ringCentralPhones` "
                                  + "for all of them; kept for clients written before a "
                                  + "person could have several. Null when none is set.",
                    example = "+13055551234") String ringCentralPhone,
            @Schema(description = "Every RingCentral extension this person's calls and SMS are "
                                  + "logged against, in the order they were entered. Leads work "
                                  + "more than one, and the activity report adds them all into a "
                                  + "single row. Empty when none is set.")
            List<String> ringCentralPhones,
            @Schema(description = "The name the monday.com board uses for this person. The "
                                  + "recruiting chart is keyed on it. Null when not set.",
                    example = "Alex") String mondayName,
            @Schema(description = "The day this person started, `YYYY-MM-DD`. Null when nobody has "
                                  + "recorded it, in which case the recruiting standard infers "
                                  + "their tenure from their first hire on the board.",
                    example = "2026-06-01") String employmentStartDate,
            @Schema(description = "ACTIVE, DISABLED or LOCKED.", example = "ACTIVE") String status,
            @Schema(description = "True while the account still has to replace the password it was "
                                  + "given. Until it does, its tokens carry only "
                                  + "`auth:changeOwnPassword`.",
                    example = "false") boolean mustChangePassword,
            @Schema(description = "The roles granted to this account.")
            List<RoleAssignmentView> roles,
            @Schema(description = "Effective permissions: the union of every role above.",
                    example = "[\"user:read\",\"role:read\"]") List<String> permissions,
            @Schema(example = "2026-08-25T14:49:38Z") Instant createdAt,
            @Schema(example = "2026-08-25T15:02:11Z") Instant updatedAt,
            @Schema(description = "Null when the account has never signed in.",
                    example = "2026-08-25T15:00:03Z") Instant lastLoginAt) {
    }

    @Schema(description = "One page of results, with the cursor for the next one.")
    public record PageView<T>(
            @Schema(description = "The rows on this page.") List<T> items,
            @Schema(description = "Pass this back as the `cursor` parameter to fetch the next page. "
                                  + "Null on the last page.",
                    example = "eyJpZCI6IjJkZGVjMTljIn0") String nextCursor,
            @Schema(description = "False when this is the last page.", example = "true")
            boolean hasMore) {
    }

    @Schema(description = "A role: a named set of permissions.")
    public record RoleView(
            @Schema(description = "The value to pass as `roleCode` when assigning this role.",
                    example = "AUDITOR") String code,
            @Schema(example = "Auditor") String name,
            @Schema(example = "Read-only access to users, roles and the audit log") String description,
            @Schema(description = "System roles ship with the product and cannot be deleted.",
                    example = "true") boolean system,
            @Schema(example = "[\"user:read\",\"role:read\",\"audit:read\"]") List<String> permissions,
            @Schema(description = "How many accounts currently hold this role.", example = "3")
            int userCount) {
    }

    @Schema(description = "One entry in the audit trail. Entries are never edited or removed.")
    public record AuditEntryView(
            @Schema(description = "Also the paging cursor: pass the last one you saw as `cursor`.",
                    example = "142") long id,
            @Schema(example = "2026-08-25T16:56:23Z") Instant occurredAt,
            @Schema(description = "Who did it. Null for an action with no identified actor, such as "
                                  + "a failed login with an unknown username.",
                    example = "admin") String actorUsername,
            @Schema(description = "Null when the actor's account no longer exists.",
                    example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338") String actorId,
            @Schema(description = "What happened, as `area.verb`.", example = "user.create")
            String action,
            @Schema(description = "What it happened to.", example = "user") String targetType,
            @Schema(example = "fe453cec-7e17-4778-9eff-82b1b9243408") String targetId,
            @Schema(description = "SUCCESS, DENIED or FAILURE.", example = "SUCCESS") String outcome,
            @Schema(description = "Where the request came from.", example = "10.0.0.14") String clientIp,
            @Schema(example = "PostmanRuntime/7.56.1") String userAgent,
            @Schema(description = "Ties this entry to one request. The same id is in the "
                                  + "`X-Correlation-Id` response header, so a report from a user "
                                  + "can be matched to what the system recorded.",
                    example = "7dc9d124-9fb2-4e61-90fd-15eabad3609a") String correlationId,
            @Schema(description = "Action-specific extras, as a JSON object. Always present; `{}` "
                                  + "when the action carried none.",
                    example = "{\"roles\":[\"HR_LEAD@JM\"]}")
            @JsonRawValue String detail) {
    }

    @Schema(description = "One signed-in device or client.")
    public record SessionView(
            @Schema(description = "Pass this to the revoke endpoint to end just this session.",
                    example = "aae76810-997a-4412-b4a5-637e3a21e6eb") String sessionId,
            @Schema(description = "The address the session was opened from.", example = "10.0.0.14")
            String clientIp,
            @Schema(description = "What the client called itself when signing in.",
                    example = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)") String userAgent,
            @Schema(example = "2026-08-25T16:49:20Z") Instant openedAt) {
    }

    @Schema(description = "A single permission from the system's vocabulary.")
    public record PermissionView(
            @Schema(description = "The code to put in a role's `permissions` array.",
                    example = "user:create") String code,
            @Schema(description = "What it acts on.", example = "user") String resource,
            @Schema(description = "What it allows.", example = "create") String action,
            @Schema(example = "Create a user account") String description) {
    }

    public static UserView of(User user) {
        return new UserView(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                emptyToNull(user.getEmail()),
                emptyToNull(user.getPhone()),
                emptyToNull(user.getRingCentralPhone()),
                user.getRingCentralPhonesList(),
                emptyToNull(user.getMondayName()),
                emptyToNull(user.getEmploymentStartDate()),
                // Strip the proto prefix so the API speaks ACTIVE, not USER_STATUS_ACTIVE.
                user.getStatus().name().replace("USER_STATUS_", ""),
                user.getMustChangePassword(),
                user.getRolesList().stream().map(Views::of).toList(),
                List.copyOf(user.getPermissionsList()),
                toInstant(user.getCreatedAt().getSeconds()),
                toInstant(user.getUpdatedAt().getSeconds()),
                user.getLastLoginAt().getSeconds() == 0 ? null : toInstant(user.getLastLoginAt().getSeconds()));
    }

    public static RoleAssignmentView of(RoleAssignment assignment) {
        return new RoleAssignmentView(
                assignment.getRoleCode(),
                // UNSPECIFIED means the grant covers every company; null says that plainly.
                assignment.getEntity() == Entity.ENTITY_UNSPECIFIED
                        ? null
                        : assignment.getEntity().name().replace("ENTITY_", ""));
    }

    public static PageView<UserView> of(ListUsersResponse response) {
        return new PageView<>(
                response.getUsersList().stream().map(Views::of).toList(),
                emptyToNull(response.getPage().getNextCursor()),
                response.getPage().getHasMore());
    }

    public static RoleView of(Role role) {
        return new RoleView(
                role.getCode(),
                role.getName(),
                role.getDescription(),
                role.getSystem(),
                List.copyOf(role.getPermissionsList()),
                role.getUserCount());
    }

    public static List<RoleView> of(ListRolesResponse response) {
        return response.getRolesList().stream().map(Views::of).toList();
    }

    public static PermissionView of(Permission permission) {
        return new PermissionView(
                permission.getCode(),
                permission.getResource(),
                permission.getAction(),
                permission.getDescription());
    }

    public static List<PermissionView> of(ListPermissionsResponse response) {
        return response.getPermissionsList().stream().map(Views::of).toList();
    }

    public static AuditEntryView of(AuditEntry entry) {
        return new AuditEntryView(
                entry.getId(),
                toInstant(entry.getOccurredAt().getSeconds()),
                emptyToNull(entry.getActorUsername()),
                emptyToNull(entry.getActorId()),
                entry.getAction(),
                emptyToNull(entry.getTargetType()),
                emptyToNull(entry.getTargetId()),
                entry.getOutcome(),
                emptyToNull(entry.getClientIp()),
                emptyToNull(entry.getUserAgent()),
                emptyToNull(entry.getCorrelationId()),
                // The service stores detail as a JSON object and hands it over as text. Wrapping it
                // in RawValue keeps it an object in the response instead of a quoted string the
                // client would have to parse a second time.
                rawJson(entry.getDetailJson()));
    }

    public static PageView<AuditEntryView> of(ListAuditResponse response) {
        return new PageView<>(
                response.getEntriesList().stream().map(Views::of).toList(),
                emptyToNull(response.getPage().getNextCursor()),
                response.getPage().getHasMore());
    }

    public static SessionView of(SessionInfo session) {
        return new SessionView(
                session.getSessionId(),
                emptyToNull(session.getClientIp()),
                emptyToNull(session.getUserAgent()),
                session.getOpenedAt().getSeconds() == 0
                        ? null
                        : toInstant(session.getOpenedAt().getSeconds()));
    }

    public static List<SessionView> of(ListSessionsResponse response) {
        return response.getSessionsList().stream().map(Views::of).toList();
    }

    /**
     * The detail column is jsonb and is read back as text. {@code @JsonRawValue} on the record
     * component emits it as the object it already is, so a client does not have to decode the same
     * JSON a second time. Only the annotation package is touched, not databind — Spring Boot 4
     * moved Jackson's core to new packages and this project stays clear of them on purpose.
     */
    private static String rawJson(String json) {
        return json == null || json.isBlank() ? "{}" : json;
    }

    private static Instant toInstant(long epochSeconds) {
        return epochSeconds == 0 ? null : Instant.ofEpochSecond(epochSeconds);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
