package uz.drenix.identity.user.grpc;

import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.drenix.identity.grpc.v1.AssignRolesRequest;
import uz.drenix.identity.grpc.v1.ChangeOwnPasswordRequest;
import uz.drenix.identity.grpc.v1.CreateUserRequest;
import uz.drenix.identity.grpc.v1.DeleteUserRequest;
import uz.drenix.identity.grpc.v1.Empty;
import uz.drenix.identity.grpc.v1.GetUserRequest;
import uz.drenix.identity.grpc.v1.ListAuditRequest;
import uz.drenix.identity.grpc.v1.ListAuditResponse;
import uz.drenix.identity.grpc.v1.ListUsersRequest;
import uz.drenix.identity.grpc.v1.ListUsersResponse;
import uz.drenix.identity.grpc.v1.PageInfo;
import uz.drenix.identity.grpc.v1.ResetPasswordRequest;
import uz.drenix.identity.grpc.v1.UnlockUserRequest;
import uz.drenix.identity.grpc.v1.UpdateUserRequest;
import uz.drenix.identity.grpc.v1.User;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;
import uz.drenix.identity.grpc.v1.VerifyCredentialsRequest;
import uz.drenix.identity.grpc.v1.VerifyCredentialsResponse;
import uz.drenix.identity.user.domain.UserEntity;
import uz.drenix.identity.user.service.AuditQueryService;
import uz.drenix.identity.user.service.AuditService;
import uz.drenix.identity.user.service.CredentialService;
import uz.drenix.identity.user.service.UserAdminService;
import uz.drenix.platform.security.AuthPrincipal;
import uz.drenix.platform.security.GrpcAuthContext;
import uz.drenix.platform.security.Permissions;

/**
 * gRPC surface for user administration.
 *
 * <p>Every method opens with a {@code GrpcAuthContext.require(...)} line. That is the entire
 * authorization story for this service: one visible statement per RPC, easy to review, impossible
 * to disable by mistyping an annotation.
 */
@Service
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {

    private final UserAdminService admin;
    private final CredentialService credentials;
    private final AuditService audit;
    private final AuditQueryService auditQuery;

    public UserGrpcService(UserAdminService admin, CredentialService credentials,
                           AuditService audit, AuditQueryService auditQuery) {
        this.admin = admin;
        this.credentials = credentials;
        this.audit = audit;
        this.auditQuery = auditQuery;
    }

    @Override
    public void createUser(CreateUserRequest request, StreamObserver<User> observer) {
        AuthPrincipal actor = GrpcAuthContext.require(Permissions.USER_CREATE);
        UserEntity created = admin.create(
                actor,
                request.getUsername(),
                request.getFullName(),
                request.getEmail(),
                request.getPhone(),
                phones(request.getRingCentralPhonesList(), request.getRingCentralPhone()),
                request.getMondayName(),
                request.getEmploymentStartDate(),
                request.getInitialPassword(),
                ProtoMapper.toGrants(request.getRolesList()),
                null,
                GrpcAuthContext.correlationId());
        respond(observer, ProtoMapper.toProto(created, admin.permissionsOf(created)));
    }

    /**
     * Read by an administrator, or by auth-service during a token refresh. The refresh case has no
     * user token by definition — the one being replaced has expired — so it is authorized by the
     * peer certificate instead, and only auth-service may use that path.
     */
    @Override
    public void getUser(GetUserRequest request, StreamObserver<User> observer) {
        if (GrpcAuthContext.principalOrNull() == null) {
            GrpcAuthContext.requirePeer(Set.of("auth-service"));
        } else {
            GrpcAuthContext.require(Permissions.USER_READ);
        }
        UserEntity user = admin.get(UUID.fromString(request.getId()));
        respond(observer, ProtoMapper.toProto(user, admin.permissionsOf(user)));
    }

    @Override
    public void listUsers(ListUsersRequest request, StreamObserver<ListUsersResponse> observer) {
        // Or notification-service, which needs the directory to work out who supervises whom.
        GrpcAuthContext.requirePermissionOrPeer(Permissions.USER_READ);
        UserAdminService.Page page = admin.list(
                request.getPage().getSearch(),
                request.getPage().getCursor(),
                request.getPage().getLimit());

        ListUsersResponse.Builder response = ListUsersResponse.newBuilder()
                .setPage(PageInfo.newBuilder()
                        .setNextCursor(page.nextCursor())
                        .setHasMore(page.hasMore())
                        .build());
        AuthPrincipal caller = GrpcAuthContext.principalOrNull();
        page.users().stream()
                .filter(user -> visibleTo(caller, user))
                .forEach(u -> response.addUsers(ProtoMapper.toProto(u, admin.permissionsOf(u))));
        respond(observer, response.build());
    }

    /**
     * Whether this caller may see this account at all.
     *
     * <p>{@code user:read} says the directory may be read; it does not say the whole company is
     * yours to read. An HR at JM held it and was answered with every account in the system — BP's
     * recruiters, BP's lead, and the administrators. Every other endpoint in this system scopes by
     * company; this one did not, and it is the one that lists people by name.
     *
     * <p>Applied here rather than at the gateway because this is where the permission is checked.
     * A rule enforced one layer up is a rule the next caller of this RPC will not have.
     *
     * <p>Three cases pass. A peer call has no principal at all — notification-service needs the
     * whole directory to work out who supervises whom, and it is reached only over mTLS with its
     * own certificate. A global grant covers every company, which is what an owner, the CEO and an
     * administrator hold. And everybody may always see their own account, whatever it is scoped to.
     *
     * <p>Accounts whose every grant is global are <em>not</em> visible to a company-scoped caller.
     * They belong to no company, so there is no company they share, and listing them would hand
     * every recruiter the administrator roster.
     *
     * <p>Filtering happens after the page is read, so a page can come back shorter than the limit
     * asked for. The cursor still describes the underlying rows, so paging remains correct — a
     * client must page until {@code hasMore} is false rather than until a page looks full.
     */
    private static boolean visibleTo(AuthPrincipal caller, UserEntity user) {
        if (caller == null
                || caller.entityScopes().isEmpty()
                || caller.entityScopes().contains(AuthPrincipal.EntityScope.GLOBAL)) {
            return true;
        }
        if (caller.userId().equals(user.getId())) {
            return true;
        }
        return user.getRoles().stream().anyMatch(role -> switch (role.getEntity()) {
            case JM -> caller.canActOn(AuthPrincipal.EntityScope.JM);
            case BP -> caller.canActOn(AuthPrincipal.EntityScope.BP);
            case null -> false;
        });
    }

    @Override
    public void updateUser(UpdateUserRequest request, StreamObserver<User> observer) {
        AuthPrincipal actor = GrpcAuthContext.require(Permissions.USER_UPDATE);
        Set<String> mask = new LinkedHashSet<>(request.getUpdateMaskList());
        UserEntity updated = admin.update(
                actor,
                UUID.fromString(request.getId()),
                mask,
                request.getFullName(),
                request.getEmail(),
                request.getPhone(),
                phones(request.getRingCentralPhonesList(), request.getRingCentralPhone()),
                request.getMondayName(),
                request.getEmploymentStartDate(),
                mask.contains("status") ? ProtoMapper.fromProto(request.getStatus()) : null,
                null,
                GrpcAuthContext.correlationId());
        respond(observer, ProtoMapper.toProto(updated, admin.permissionsOf(updated)));
    }

    @Override
    public void deleteUser(DeleteUserRequest request, StreamObserver<Empty> observer) {
        AuthPrincipal actor = GrpcAuthContext.require(Permissions.USER_DELETE);
        admin.softDelete(actor, UUID.fromString(request.getId()), request.getReason(),
                null, GrpcAuthContext.correlationId());
        respond(observer, Empty.getDefaultInstance());
    }

    @Override
    public void assignRoles(AssignRolesRequest request, StreamObserver<User> observer) {
        AuthPrincipal actor = GrpcAuthContext.require(Permissions.USER_ASSIGN_ROLE);
        UserEntity updated = admin.assignRoles(
                actor,
                UUID.fromString(request.getUserId()),
                ProtoMapper.toGrants(request.getRolesList()),
                null,
                GrpcAuthContext.correlationId());
        respond(observer, ProtoMapper.toProto(updated, admin.permissionsOf(updated)));
    }

    @Override
    public void resetPassword(ResetPasswordRequest request, StreamObserver<Empty> observer) {
        AuthPrincipal actor = GrpcAuthContext.require(Permissions.USER_RESET_PASSWORD);
        admin.resetPassword(actor, UUID.fromString(request.getUserId()), request.getNewPassword(),
                null, GrpcAuthContext.correlationId());
        respond(observer, Empty.getDefaultInstance());
    }

    @Override
    public void changeOwnPassword(ChangeOwnPasswordRequest request, StreamObserver<Empty> observer) {
        AuthPrincipal actor = GrpcAuthContext.require(Permissions.AUTH_CHANGE_OWN_PASSWORD);
        UUID target = UUID.fromString(request.getUserId());
        // A password-change token is scoped to its own account. Without this check the
        // limited token issued during a forced change would be able to rewrite anyone's password.
        if (!actor.userId().equals(target)) {
            throw new uz.drenix.platform.security.AccessDeniedException(Permissions.USER_RESET_PASSWORD);
        }
        admin.changeOwnPassword(target, request.getCurrentPassword(), request.getNewPassword(),
                null, GrpcAuthContext.correlationId());
        respond(observer, Empty.getDefaultInstance());
    }

    @Override
    public void unlockUser(UnlockUserRequest request, StreamObserver<User> observer) {
        AuthPrincipal actor = GrpcAuthContext.require(Permissions.USER_UPDATE);
        UserEntity unlocked = admin.unlock(actor, UUID.fromString(request.getId()),
                null, GrpcAuthContext.correlationId());
        respond(observer, ProtoMapper.toProto(unlocked, admin.permissionsOf(unlocked)));
    }

    @Override
    public void listAudit(ListAuditRequest request, StreamObserver<ListAuditResponse> observer) {
        // Or notification-service, which turns these entries into notifications.
        GrpcAuthContext.requirePermissionOrPeer(Permissions.AUDIT_READ);

        AuditQueryService.Page page = auditQuery.list(
                new AuditQueryService.Filter(
                        emptyToNull(request.getActorUsername()),
                        emptyToNull(request.getAction()),
                        emptyToNull(request.getOutcome()),
                        emptyToNull(request.getTargetId()),
                        toInstant(request.hasOccurredFrom() ? request.getOccurredFrom() : null),
                        toInstant(request.hasOccurredTo() ? request.getOccurredTo() : null)),
                request.getPage().getCursor(),
                request.getPage().getLimit());

        ListAuditResponse.Builder response = ListAuditResponse.newBuilder()
                .setPage(PageInfo.newBuilder()
                        .setNextCursor(page.nextCursor() == null ? "" : page.nextCursor())
                        .setHasMore(page.hasMore())
                        .build());
        page.entries().forEach(e -> response.addEntries(ProtoMapper.toProto(e)));
        respond(observer, response.build());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static java.time.Instant toInstant(com.google.protobuf.Timestamp timestamp) {
        return timestamp == null ? null : java.time.Instant.ofEpochSecond(timestamp.getSeconds());
    }

    /**
     * Called by auth-service only, over mTLS. This RPC is exempt from the bearer-token
     * requirement because it runs before any token exists; the peer certificate is the
     * authentication.
     */
    @Override
    public void verifyCredentials(VerifyCredentialsRequest request,
                                  StreamObserver<VerifyCredentialsResponse> observer) {

        CredentialService.Result result = credentials.verify(request.getUsername(), request.getPassword());
        VerifyCredentialsResponse.Builder response = VerifyCredentialsResponse.newBuilder()
                .setAttemptsRemaining(result.attemptsRemaining());

        switch (result.verdict()) {
            case OK -> {
                UserEntity user = result.user();
                response.setResult(VerifyCredentialsResponse.Result.RESULT_OK)
                        .setUser(ProtoMapper.toProto(user, admin.permissionsOf(user)));
                audit.record(user.getId(), user.getUsername(), "auth.login", "user",
                        user.getId().toString(), AuditService.Outcome.SUCCESS,
                        request.getClientIp(), request.getUserAgent(), GrpcAuthContext.correlationId(),
                        java.util.Map.of());
            }
            case LOCKED -> {
                response.setResult(VerifyCredentialsResponse.Result.RESULT_LOCKED);
                if (result.lockedUntil() != null) {
                    response.setLockedUntil(ProtoMapper.toProto(result.lockedUntil()));
                }
            }
            case DISABLED -> response.setResult(VerifyCredentialsResponse.Result.RESULT_DISABLED);
            case INVALID_CREDENTIALS -> {
                response.setResult(VerifyCredentialsResponse.Result.RESULT_INVALID_CREDENTIALS);
                audit.record(null, request.getUsername(), "auth.login", "user", null,
                        AuditService.Outcome.FAILURE, request.getClientIp(), request.getUserAgent(),
                        GrpcAuthContext.correlationId(),
                        java.util.Map.of("attemptsRemaining", result.attemptsRemaining()));
            }
        }

        respond(observer, response.build());
    }

    private static <T> void respond(StreamObserver<T> observer, T value) {
        observer.onNext(value);
        observer.onCompleted();
    }

    /**
     * The repeated field, falling back to the singular one.
     *
     * <p>A caller that has not been updated since people could have several numbers still sends
     * {@code ring_central_phone}; treating it as a list of one keeps those clients working without
     * giving them a second way to say the same thing when they do send the list.
     */
    private static List<String> phones(List<String> list, String single) {
        if (!list.isEmpty()) {
            return list;
        }
        return single == null || single.isBlank() ? List.of() : List.of(single);
    }
}
