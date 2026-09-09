package uz.drenix.edge.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import uz.drenix.identity.grpc.v1.Entity;
import uz.drenix.identity.grpc.v1.ListUsersRequest;
import uz.drenix.identity.grpc.v1.PageRequest;
import uz.drenix.identity.grpc.v1.RoleAssignment;
import uz.drenix.identity.grpc.v1.User;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;
import uz.drenix.platform.security.AuthPrincipal;
import uz.drenix.platform.security.Permissions;

/**
 * Whose figures a caller may see.
 *
 * <p>One rule, in one place, because there is now more than one endpoint that needs it: the live
 * activity report, the team report and the written feedback must agree exactly on who is in scope.
 * Two copies of this would drift, and the way it would show is somebody's export quietly listing a
 * person their dashboard does not.
 *
 * <p>Decided at the gateway rather than downstream, because this is where the token and the user
 * directory meet. performance-service is handed a list of people and reports on exactly those.
 */
@Component
public class TeamDirectory {

    private final UserServiceGrpc.UserServiceBlockingStub userService;

    public TeamDirectory(UserServiceGrpc.UserServiceBlockingStub userService) {
        this.userService = userService;
    }

    /**
     * The accounts this caller may see, keyed by account id.
     *
     * <p>Keyed by id, not by number, because a person can work several: keying by number would put
     * a lead in the answer twice and halve both of their rows.
     *
     * <p>Accounts without a RingCentral number are left out. There is nothing to match them
     * against, and an empty row for them reads as somebody who did no work.
     */
    public Map<String, User> visibleTo(AuthPrincipal principal) {
        Map<String, User> byId = new LinkedHashMap<>();

        if (principal.hasPermission(Permissions.PERFORMANCE_READ_ALL)) {
            everyone().stream()
                    .filter(u -> u.getRingCentralPhonesCount() > 0)
                    .forEach(u -> byId.put(u.getId(), u));
            return byId;
        }

        if (principal.hasPermission(Permissions.PERFORMANCE_READ_TEAM)) {
            // Team means company. A grant with no entity is global, so its holder sees everyone —
            // which is what an unscoped HR_LEAD is.
            boolean global = principal.entityScopes().contains(AuthPrincipal.EntityScope.GLOBAL)
                             || principal.entityScopes().isEmpty();
            for (User user : everyone()) {
                if (user.getRingCentralPhonesCount() == 0) {
                    continue;
                }
                if (global || sharesCompany(principal, user)) {
                    byId.put(user.getId(), user);
                }
            }
            return byId;
        }

        // performance:read — themselves, and only if their own account carries a number.
        User self = userService.getUser(uz.drenix.identity.grpc.v1.GetUserRequest.newBuilder()
                .setId(principal.userId().toString())
                .build());
        if (self.getRingCentralPhonesCount() > 0) {
            byId.put(self.getId(), self);
        }
        return byId;
    }

    /**
     * The company whose RingCentral account this person's numbers live in.
     *
     * <p>The first company on their role grants. Nobody here belongs to two: JM and BP are
     * separate teams with separate phone systems. Unspecified when every grant they hold is
     * global, which performance-service reads as "the default account".
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

    /**
     * Narrows a set of people to one company.
     *
     * <p>A monthly report is a report on <em>one</em> MS. Every ranking, scorecard and conclusion
     * in it compares the people inside it against each other, and JM and BP share neither a
     * recruiting board, a phone system nor a manager — so a league table spanning both would be
     * arithmetic without meaning, quite apart from showing one company's figures to the other's
     * lead.
     *
     * <p>Somebody whose every role grant is global belongs to no company and is left out. That is
     * deliberate: an administrator is not a recruiter in either MS, and the alternative — putting
     * them in both — is the mixing this exists to prevent. The practical consequence is that an HR
     * must be granted their role <em>with</em> a company to appear in that company's report, which
     * is already true of which RingCentral account their calls are read from.
     */
    public static Map<String, User> inCompany(Map<String, User> users, Entity company) {
        Map<String, User> scoped = new LinkedHashMap<>();
        users.forEach((id, user) -> {
            if (companyOf(user) == company) {
                scoped.put(id, user);
            }
        });
        return scoped;
    }

    /**
     * What this person does, in the words a report should print.
     *
     * <p>Taken from the roles they hold rather than stored separately, so it cannot drift from
     * what they can actually do. The written report leans on it: the analysis of a lead's own
     * recruiting is a different question from the analysis of their team's, and only this
     * distinguishes them.
     */
    public static String positionOf(User user) {
        for (RoleAssignment role : user.getRolesList()) {
            String position = POSITIONS.get(role.getRoleCode());
            if (position != null) {
                return position;
            }
        }
        return "HR";
    }

    /**
     * The first of somebody's roles that names a job. The same rule as {@link #companyOf}, so a
     * person's company and their title are read off the same grant rather than off two.
     */
    private static final Map<String, String> POSITIONS = Map.of(
            "OWNER", "Owner",
            "CEO", "CEO",
            "SUPER_ADMIN", "Administrator",
            "HR_LEAD", "HR Leader",
            "HR", "HR");

    private static boolean sharesCompany(AuthPrincipal principal, User user) {
        return user.getRolesList().stream().anyMatch(role -> switch (role.getEntity()) {
            case ENTITY_JM -> principal.canActOn(AuthPrincipal.EntityScope.JM);
            case ENTITY_BP -> principal.canActOn(AuthPrincipal.EntityScope.BP);
            // A grant with no company belongs to everyone's company.
            case ENTITY_UNSPECIFIED, UNRECOGNIZED -> true;
        });
    }

    /**
     * Every account, walked page by page.
     *
     * <p>The call carries the caller's own token, so somebody without {@code user:read} cannot
     * reach it — which is exactly why {@code performance:read} resolves only their own record.
     */
    private List<User> everyone() {
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
}
