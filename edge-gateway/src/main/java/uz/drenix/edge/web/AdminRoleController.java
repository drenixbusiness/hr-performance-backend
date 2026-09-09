package uz.drenix.edge.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.drenix.identity.grpc.v1.CreateRoleRequest;
import uz.drenix.identity.grpc.v1.DeleteRoleRequest;
import uz.drenix.identity.grpc.v1.Empty;
import uz.drenix.edge.web.dto.Views;
import uz.drenix.identity.grpc.v1.RoleServiceGrpc;
import uz.drenix.identity.grpc.v1.UpdateRoleRequest;

@RestController
@RequestMapping("/api/v1/admin/roles")
@Tag(name = "Admin · Roles")
public class AdminRoleController {

    @Schema(description = "A new role and the permissions it grants.")
    public record CreateRoleBody(
            @Schema(description = "Stable identifier, and the value you pass as `roleCode` when "
                                  + "assigning it. Uppercase letters, digits and underscore, 2–48 "
                                  + "characters, starting with a letter. It cannot be changed "
                                  + "later.",
                    example = "RECRUITER", requiredMode = Schema.RequiredMode.REQUIRED)
            // Two characters minimum, matching roles_code_format in V4. Short department codes
            // such as HR, IT and QA are ordinary; a three-character floor rejected them.
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,47}$",
                               message = "uppercase letters, digits and underscore")
            String code,

            @Schema(description = "Human-readable name, shown in the UI.",
                    example = "Recruiter", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 120) String name,

            @Schema(description = "Optional note on what the role is for.",
                    example = "Reads candidate records and manages interviews")
            @Size(max = 500) String description,

            @Schema(description = "Permission codes this role grants. Take them from "
                                  + "`GET /api/v1/admin/roles/permissions` — an unknown code is "
                                  + "rejected with 400.",
                    example = "[\"user:read\",\"role:read\"]")
            @Size(max = 200) List<@NotBlank @Size(max = 64) String> permissions) {
    }

    /**
     * The update body carries no {@code code}: the role to edit is the one in the path. Sharing
     * the create record here made {@code code} a required field that was then ignored, so a
     * correct request was rejected for omitting it and a wrong one silently edited the path role.
     */
    @Schema(description = """
            The new name, description and permission set. No `code` — the role being edited is the \
            one in the path, and a role's code never changes.""")
    public record UpdateRoleBody(
            @Schema(description = "Human-readable name, shown in the UI.",
                    example = "Senior Recruiter", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 120) String name,

            @Schema(description = "Optional note on what the role is for.",
                    example = "Reads candidate records, manages interviews and offers")
            @Size(max = 500) String description,

            @Schema(description = "The complete permission set. This **replaces** the existing one, "
                                  + "so send every permission the role should keep.",
                    example = "[\"user:read\",\"role:read\",\"audit:read\"]")
            @Size(max = 200) List<@NotBlank @Size(max = 64) String> permissions) {
    }

    private final RoleServiceGrpc.RoleServiceBlockingStub roleService;

    public AdminRoleController(RoleServiceGrpc.RoleServiceBlockingStub roleService) {
        this.roleService = roleService;
    }

    @Operation(
            summary = "List roles",
            description = """
                    Every role, with its permissions and how many accounts hold it. Needs \
                    `role:read`.

                    Four roles ship with the system and cannot be deleted: `OWNER` (every \
                    permission, every company — grant it with no `entity`), `SUPER_ADMIN` \
                    (everything except `performance:readTeam`, which its `readAll` supersedes), \
                    `USER_ADMIN` (manages accounts and role assignments) and `AUDITOR` \
                    (read-only plus the audit log). They come back with `system: true`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All roles."),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `role:read`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping
    @PreAuthorize("hasAuthority('role:read')")
    public List<Views.RoleView> list() {
        return Views.of(roleService.listRoles(Empty.getDefaultInstance()));
    }

    @Operation(
            summary = "List available permissions",
            description = """
                    The whole permission vocabulary. Needs `role:read`.

                    **Call this before creating or editing a role** — these are the only strings \
                    the `permissions` array accepts, and anything else is rejected with 400.

                    Codes read `resource:action`, for example `user:create` or `audit:read`. New \
                    permissions arrive only through a database migration, never at runtime.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every permission the system defines."),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `role:read`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('role:read')")
    public List<Views.PermissionView> permissions() {
        return Views.of(roleService.listPermissions(Empty.getDefaultInstance()));
    }

    @Operation(
            summary = "Create a role",
            description = """
                    Defines a new role. Needs `role:create`.

                    Pick permissions from `GET /api/v1/admin/roles/permissions`. The `code` becomes \
                    the role's permanent identifier — it is what you pass as `roleCode` when \
                    assigning the role, and it cannot be renamed afterwards.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Created. The role is returned."),
            @ApiResponse(responseCode = "400",
                    description = "The code does not match the required shape, or a permission "
                                  + "code is unknown.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `role:create`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "A role with that code already exists.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PostMapping
    @PreAuthorize("hasAuthority('role:create')")
    public Views.RoleView create(@Valid @RequestBody CreateRoleBody body) {
        return Views.of(roleService.createRole(CreateRoleRequest.newBuilder()
                .setCode(body.code())
                .setName(body.name())
                .setDescription(body.description() == null ? "" : body.description())
                .addAllPermissions(body.permissions() == null ? List.of() : body.permissions())
                .build()));
    }

    @Operation(
            summary = "Update a role",
            description = """
                    Rewrites a role's name, description and permission set. Needs `role:update`.

                    **The permission list replaces the old one.** Send every permission the role \
                    should end up with, not just the additions.

                    The role is identified by the path; the body carries no `code`, and a role's \
                    code never changes.

                    Everyone holding the role feels the change within one access-token lifetime \
                    (10 minutes), or immediately on their next refresh.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated. The role is returned."),
            @ApiResponse(responseCode = "400", description = "A permission code is unknown.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `role:update`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No role with that code.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PutMapping("/{code}")
    @PreAuthorize("hasAuthority('role:update')")
    public Views.RoleView update(
            @Parameter(description = "The role's code.", example = "RECRUITER", required = true)
            @PathVariable String code,
            @Valid @RequestBody UpdateRoleBody body) {
        return Views.of(roleService.updateRole(UpdateRoleRequest.newBuilder()
                .setCode(code)
                .setName(body.name())
                .setDescription(body.description() == null ? "" : body.description())
                .addAllPermissions(body.permissions() == null ? List.of() : body.permissions())
                .build()));
    }

    @Operation(
            summary = "Delete a role",
            description = """
                    Removes a role. Needs `role:delete`.

                    The four system roles (`OWNER`, `SUPER_ADMIN`, `USER_ADMIN`, `AUDITOR`) cannot \
                    be deleted, and neither can a role that accounts still hold — reassign those \
                    accounts first. Both refusals come back as 409.

                    Answers **200** with a small JSON body rather than an empty 204, so a client \
                    that parses every response as JSON does not throw on a call that succeeded.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deleted."),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `role:delete`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No role with that code.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "It is a system role, or accounts still hold it.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @DeleteMapping("/{code}")
    @PreAuthorize("hasAuthority('role:delete')")
    public DeletedBody delete(
            @Parameter(description = "The role's code.", example = "RECRUITER", required = true)
            @PathVariable String code) {
        roleService.deleteRole(DeleteRoleRequest.newBuilder().setCode(code).build());
        return new DeletedBody(code, true);
    }

    @Schema(description = "Confirmation that the role is gone.")
    public record DeletedBody(
            @Schema(example = "RECRUITER") String code,
            @Schema(example = "true") boolean deleted) {
    }
}
