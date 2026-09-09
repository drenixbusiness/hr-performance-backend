package uz.drenix.identity.user.grpc;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import uz.drenix.identity.grpc.v1.Entity;
import uz.drenix.identity.grpc.v1.RoleAssignment;
import uz.drenix.identity.grpc.v1.User;
import uz.drenix.identity.grpc.v1.UserStatus;
import uz.drenix.identity.user.domain.Entity4;
import uz.drenix.identity.user.service.AuditQueryService;
import uz.drenix.identity.user.domain.UserEntity;
import uz.drenix.identity.user.service.UserAdminService.RoleGrant;

/** Domain to protobuf. Nothing here ever copies a password hash. */
final class ProtoMapper {

    private ProtoMapper() {
    }

    static User toProto(UserEntity user, Set<String> permissions) {
        User.Builder builder = User.newBuilder()
                .setId(user.getId().toString())
                .setUsername(user.getUsername())
                .setFullName(user.getFullName())
                .setEmail(nullToEmpty(user.getEmail()))
                .setPhone(nullToEmpty(user.getPhone()))
                // Both shapes: the list is the truth, the singular is the first of it so that a
                // client written before people had two numbers still reads something sensible.
                .addAllRingCentralPhones(user.getRingCentralPhones())
                .setRingCentralPhone(user.getRingCentralPhones().isEmpty()
                        ? "" : user.getRingCentralPhones().getFirst())
                .setMondayName(nullToEmpty(user.getMondayName()))
                .setEmploymentStartDate(user.getEmploymentStartDate() == null
                        ? "" : user.getEmploymentStartDate().toString())
                .setStatus(toProto(user.getStatus()))
                .setMustChangePassword(user.isMustChangePassword())
                .setCreatedAt(toProto(user.getCreatedAt()))
                .setUpdatedAt(toProto(user.getUpdatedAt()))
                .setCreatedBy(user.getCreatedBy() == null ? "" : user.getCreatedBy().toString());

        if (user.getLastLoginAt() != null) {
            builder.setLastLoginAt(toProto(user.getLastLoginAt()));
        }
        user.getRoles().forEach(role -> builder.addRoles(RoleAssignment.newBuilder()
                .setRoleCode(role.getRoleCode())
                .setEntity(toProto(role.getEntity()))
                .build()));
        builder.addAllPermissions(permissions);
        return builder.build();
    }

    static UserStatus toProto(uz.drenix.identity.user.domain.UserStatus status) {
        return switch (status) {
            case ACTIVE -> UserStatus.USER_STATUS_ACTIVE;
            case DISABLED -> UserStatus.USER_STATUS_DISABLED;
            case LOCKED -> UserStatus.USER_STATUS_LOCKED;
        };
    }

    static uz.drenix.identity.user.domain.UserStatus fromProto(UserStatus status) {
        return switch (status) {
            case USER_STATUS_ACTIVE -> uz.drenix.identity.user.domain.UserStatus.ACTIVE;
            case USER_STATUS_DISABLED -> uz.drenix.identity.user.domain.UserStatus.DISABLED;
            case USER_STATUS_LOCKED -> uz.drenix.identity.user.domain.UserStatus.LOCKED;
            case USER_STATUS_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("status must be specified");
        };
    }

    static Entity toProto(Entity4 entity) {
        if (entity == null) {
            return Entity.ENTITY_UNSPECIFIED;
        }
        return switch (entity) {
            case JM -> Entity.ENTITY_JM;
            case BP -> Entity.ENTITY_BP;
        };
    }

    /** UNSPECIFIED maps to null, which means "all entities" on a role grant. */
    static Entity4 fromProto(Entity entity) {
        return switch (entity) {
            case ENTITY_JM -> Entity4.JM;
            case ENTITY_BP -> Entity4.BP;
            case ENTITY_UNSPECIFIED, UNRECOGNIZED -> null;
        };
    }

    static uz.drenix.identity.grpc.v1.AuditEntry toProto(AuditQueryService.Entry entry) {
        uz.drenix.identity.grpc.v1.AuditEntry.Builder builder =
                uz.drenix.identity.grpc.v1.AuditEntry.newBuilder()
                        .setId(entry.id())
                        .setActorId(entry.actorId())
                        .setActorUsername(entry.actorUsername())
                        .setAction(entry.action())
                        .setTargetType(entry.targetType())
                        .setTargetId(entry.targetId())
                        .setOutcome(entry.outcome())
                        .setClientIp(entry.clientIp())
                        .setUserAgent(entry.userAgent())
                        .setCorrelationId(entry.correlationId())
                        .setDetailJson(entry.detailJson());
        if (entry.occurredAt() != null) {
            builder.setOccurredAt(toProto(entry.occurredAt()));
        }
        return builder.build();
    }

    static List<RoleGrant> toGrants(List<RoleAssignment> assignments) {
        return assignments.stream()
                .map(a -> new RoleGrant(a.getRoleCode(), fromProto(a.getEntity())))
                .toList();
    }

    static Timestamp toProto(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
