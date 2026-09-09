package uz.drenix.identity.user.grpc;

import io.grpc.stub.StreamObserver;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.drenix.identity.grpc.v1.CreateRoleRequest;
import uz.drenix.identity.grpc.v1.DeleteRoleRequest;
import uz.drenix.identity.grpc.v1.Empty;
import uz.drenix.identity.grpc.v1.GetRoleRequest;
import uz.drenix.identity.grpc.v1.ListPermissionsResponse;
import uz.drenix.identity.grpc.v1.ListRolesResponse;
import uz.drenix.identity.grpc.v1.Permission;
import uz.drenix.identity.grpc.v1.Role;
import uz.drenix.identity.grpc.v1.RoleServiceGrpc;
import uz.drenix.identity.grpc.v1.UpdateRoleRequest;
import uz.drenix.identity.user.domain.RoleEntity;
import uz.drenix.identity.user.repo.PermissionRepository;
import uz.drenix.identity.user.repo.RoleRepository;
import uz.drenix.identity.user.service.AuditService;
import uz.drenix.platform.security.AuthPrincipal;
import uz.drenix.platform.security.GrpcAuthContext;
import uz.drenix.platform.security.Permissions;

@Service
public class RoleGrpcService extends RoleServiceGrpc.RoleServiceImplBase {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final AuditService audit;

    public RoleGrpcService(RoleRepository roles, PermissionRepository permissions, AuditService audit) {
        this.roles = roles;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void createRole(CreateRoleRequest request, StreamObserver<Role> observer) {
        AuthPrincipal actor = GrpcAuthContext.require(Permissions.ROLE_CREATE);
        if (roles.existsById(request.getCode())) {
            throw new IllegalArgumentException("Role already exists: " + request.getCode());
        }
        Set<String> granted = validatePermissions(request.getPermissionsList());
        guardPrivilegeEscalation(actor, granted);

        RoleEntity role = new RoleEntity(request.getCode(), request.getName(), request.getDescription());
        role.replacePermissions(granted);
        RoleEntity saved = roles.save(role);

        audit.record(actor.userId(), actor.username(), "role.create", "role", saved.getCode(),
                AuditService.Outcome.SUCCESS, null, null, GrpcAuthContext.correlationId(),
                java.util.Map.of("permissions", granted));
        respond(observer, toProto(saved));
    }

    @Override
    @Transactional(readOnly = true)
    public void getRole(GetRoleRequest request, StreamObserver<Role> observer) {
        GrpcAuthContext.require(Permissions.ROLE_READ);
        respond(observer, toProto(loadRole(request.getCode())));
    }

    @Override
    @Transactional(readOnly = true)
    public void listRoles(Empty request, StreamObserver<ListRolesResponse> observer) {
        GrpcAuthContext.require(Permissions.ROLE_READ);
        ListRolesResponse.Builder response = ListRolesResponse.newBuilder();
        roles.findAllByOrderByCodeAsc().forEach(role -> response.addRoles(toProto(role)));
        respond(observer, response.build());
    }

    @Override
    @Transactional
    public void updateRole(UpdateRoleRequest request, StreamObserver<Role> observer) {
        AuthPrincipal actor = GrpcAuthContext.require(Permissions.ROLE_UPDATE);
        RoleEntity role = loadRole(request.getCode());
        Set<String> granted = validatePermissions(request.getPermissionsList());
        guardPrivilegeEscalation(actor, granted);

        if (!request.getName().isBlank()) {
            role.setName(request.getName());
        }
        role.setDescription(request.getDescription());
        role.replacePermissions(granted);

        audit.record(actor.userId(), actor.username(), "role.update", "role", role.getCode(),
                AuditService.Outcome.SUCCESS, null, null, GrpcAuthContext.correlationId(),
                java.util.Map.of("permissions", granted));
        respond(observer, toProto(role));
    }

    @Override
    @Transactional
    public void deleteRole(DeleteRoleRequest request, StreamObserver<Empty> observer) {
        AuthPrincipal actor = GrpcAuthContext.require(Permissions.ROLE_DELETE);
        RoleEntity role = loadRole(request.getCode());

        if (role.isSystem()) {
            throw new IllegalArgumentException("System roles cannot be deleted");
        }
        long assignments = roles.countAssignments(role.getCode());
        if (assignments > 0) {
            // Deleting a role out from under live users silently strips their access. Make the
            // admin reassign first, so the change is deliberate and visible.
            throw new IllegalArgumentException(
                    "Role is still assigned to " + assignments + " user(s); reassign them first");
        }
        roles.delete(role);

        audit.record(actor.userId(), actor.username(), "role.delete", "role", role.getCode(),
                AuditService.Outcome.SUCCESS, null, null, GrpcAuthContext.correlationId(), java.util.Map.of());
        respond(observer, Empty.getDefaultInstance());
    }

    @Override
    @Transactional(readOnly = true)
    public void listPermissions(Empty request, StreamObserver<ListPermissionsResponse> observer) {
        GrpcAuthContext.require(Permissions.ROLE_READ);
        ListPermissionsResponse.Builder response = ListPermissionsResponse.newBuilder();
        permissions.findAllByOrderByCodeAsc().forEach(p -> response.addPermissions(Permission.newBuilder()
                .setCode(p.getCode())
                .setResource(p.getResource())
                .setAction(p.getAction())
                .setDescription(p.getDescription())
                .build()));
        respond(observer, response.build());
    }

    // -----------------------------------------------------------------------

    private RoleEntity loadRole(String code) {
        return roles.findById(code).orElseThrow(() -> new NoSuchElementException("Role not found: " + code));
    }

    private Set<String> validatePermissions(java.util.List<String> requested) {
        Set<String> known = new LinkedHashSet<>();
        permissions.findAllByOrderByCodeAsc().forEach(p -> known.add(p.getCode()));
        Set<String> granted = new LinkedHashSet<>(requested);
        for (String code : granted) {
            if (!known.contains(code)) {
                throw new IllegalArgumentException("Unknown permission: " + code);
            }
        }
        return granted;
    }

    /**
     * An admin may not build a role that carries more than they themselves hold. Without this,
     * anyone with role:update can mint a role containing every permission, assign it to
     * themselves, and become a super admin in two calls.
     */
    private void guardPrivilegeEscalation(AuthPrincipal actor, Set<String> requested) {
        for (String permission : requested) {
            if (!actor.hasPermission(permission)) {
                throw new uz.drenix.platform.security.AccessDeniedException(permission);
            }
        }
    }

    private Role toProto(RoleEntity role) {
        return Role.newBuilder()
                .setCode(role.getCode())
                .setName(role.getName())
                .setDescription(role.getDescription())
                .setSystem(role.isSystem())
                .addAllPermissions(role.getPermissions())
                .setUserCount((int) roles.countAssignments(role.getCode()))
                .build();
    }

    private static <T> void respond(StreamObserver<T> observer, T value) {
        observer.onNext(value);
        observer.onCompleted();
    }
}
