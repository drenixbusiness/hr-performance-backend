package uz.drenix.identity.notification.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import uz.drenix.identity.grpc.v1.Entity;
import uz.drenix.identity.grpc.v1.ListUsersRequest;
import uz.drenix.identity.grpc.v1.PageRequest;
import uz.drenix.identity.grpc.v1.RoleAssignment;
import uz.drenix.identity.grpc.v1.User;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;

/**
 * Who works here, and who supervises whom.
 *
 * <p>The org chart this service needs is small: a notification about somebody goes to the people
 * responsible for them. That is the whole model, and it is derived from role grants rather than
 * stored, so there is no second copy of the hierarchy to fall out of date.
 */
@Component
public class Directory {

    public static final String HR_LEAD = "HR_LEAD";
    public static final String CEO = "CEO";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String OWNER = "OWNER";

    /**
     * The roles whose remit is the whole organisation, not one company.
     *
     * <p>A list rather than a permission check because notifications are about responsibility, not
     * about access: somebody could be granted { performance:readAll} for a dashboard without
     * anybody intending them to receive every refusal in both companies.
     */
    private static final Set<String> ORGANISATION_WIDE_ROLES = Set.of(CEO, SUPER_ADMIN, OWNER);

    private final UserServiceGrpc.UserServiceBlockingStub userService;

    public Directory(UserServiceGrpc.UserServiceBlockingStub userService) {
        this.userService = userService;
    }

    /** Every account, walked page by page. */
    public List<User> everyone() {
        List<User> users = new ArrayList<>();
        String cursor = "";
        // A hard stop rather than while(true): a cursor that never empties would loop forever.
        for (int page = 0; page < 100; page++) {
            var response = userService.listUsers(ListUsersRequest.newBuilder()
                    .setPage(PageRequest.newBuilder().setLimit(200).setCursor(cursor).build())
                    .build());
            users.addAll(response.getUsersList());
            cursor = response.getPage().getNextCursor();
            if (!response.getPage().getHasMore() || cursor.isBlank()) {
                break;
            }
        }
        return users;
    }

    /**
     * The people who should hear about {@code subject}.
     *
     * <p>Company leads, plus everyone who sees the whole organisation. A grant with no entity is
     * global, so an unscoped HR_LEAD hears about everybody — which is what an unscoped grant
     * means everywhere else in this system.
     *
     * <p>A lead is in their own company, so a lead who misses a target is told about themselves.
     * That is deliberate: the alternative is the one person nobody ever tells.
     */
    public List<User> supervisorsOf(User subject, List<User> everyone) {
        Set<Entity> companies = companiesOf(subject);
        List<User> recipients = new ArrayList<>();
        for (User candidate : everyone) {
            if (seesEverything(candidate) || leadsAnyOf(candidate, companies)) {
                recipients.add(candidate);
            }
        }
        return recipients;
    }

    /** OWNER, CEO and SUPER_ADMIN hear about the whole organisation. */
    public static boolean seesEverything(User user) {
        return user.getRolesList().stream()
                .anyMatch(r -> ORGANISATION_WIDE_ROLES.contains(r.getRoleCode()));
    }

    /**
     * Whether this lead is responsible for somebody in {@code companies}.
     *
     * <p>An empty {@code companies} means the subject belongs to no company at all — an
     * administrator. It returns <b>false</b>: a JM lead is not responsible for the administrator,
     * and telling them about it was how BP activity ended up in JM inboxes. Only people who see
     * the whole organisation hear about somebody who belongs to none of it.
     */
    private static boolean leadsAnyOf(User candidate, Set<Entity> companies) {
        if (companies.isEmpty()) {
            return false;
        }
        for (RoleAssignment role : candidate.getRolesList()) {
            if (!HR_LEAD.equals(role.getRoleCode())) {
                continue;
            }
            // A lead with no entity on their grant leads every company, which is what an unscoped
            // grant means everywhere else in this system.
            if (role.getEntity() == Entity.ENTITY_UNSPECIFIED
                    || companies.contains(role.getEntity())) {
                return true;
            }
        }
        return false;
    }

    /** The companies somebody belongs to. Empty when every grant they hold is global. */
    public static Set<Entity> companiesOf(User user) {
        Set<Entity> companies = new LinkedHashSet<>();
        for (RoleAssignment role : user.getRolesList()) {
            if (role.getEntity() != Entity.ENTITY_UNSPECIFIED) {
                companies.add(role.getEntity());
            }
        }
        return companies;
    }

    /**
     * The company whose RingCentral account this person's numbers live in.
     *
     * <p>The first company on their role grants. Unspecified when every grant is global, which
     * performance-service reads as "the default account".
     */
    public static Entity companyOf(User user) {
        for (RoleAssignment role : user.getRolesList()) {
            if (role.getEntity() != Entity.ENTITY_UNSPECIFIED
                    && role.getEntity() != Entity.UNRECOGNIZED) {
                return role.getEntity();
            }
        }
        return Entity.ENTITY_UNSPECIFIED;
    }

    /** The name to show. Falls back to the username, which always exists. */
    public static String displayName(User user) {
        return user.getFullName().isBlank() ? user.getUsername() : user.getFullName();
    }
}
