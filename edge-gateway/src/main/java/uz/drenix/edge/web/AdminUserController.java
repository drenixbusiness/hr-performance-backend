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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.drenix.identity.grpc.v1.AssignRolesRequest;
import uz.drenix.identity.grpc.v1.AuthServiceGrpc;
import uz.drenix.identity.grpc.v1.CreateUserRequest;
import uz.drenix.identity.grpc.v1.DeleteUserRequest;
import uz.drenix.identity.grpc.v1.Entity;
import uz.drenix.identity.grpc.v1.GetUserRequest;
import uz.drenix.edge.web.dto.Views;
import uz.drenix.identity.grpc.v1.ListSessionsRequest;
import uz.drenix.identity.grpc.v1.ListUsersRequest;
import uz.drenix.identity.grpc.v1.PageRequest;
import uz.drenix.identity.grpc.v1.ResetPasswordRequest;
import uz.drenix.identity.grpc.v1.RevokeSessionsRequest;
import uz.drenix.identity.grpc.v1.RoleAssignment;
import uz.drenix.identity.grpc.v1.UnlockUserRequest;
import uz.drenix.identity.grpc.v1.UpdateUserRequest;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;
import uz.drenix.identity.grpc.v1.UserStatus;

/**
 * Admin-only user management.
 *
 * <p>{@code @PreAuthorize} here is a fast rejection at the edge, not the actual control:
 * user-service checks the same permission again on its own side. The gateway is convenience;
 * the service is the authority.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "Admin · Users")
public class AdminUserController {

    @Schema(description = "One role grant, optionally narrowed to a single company.")
    public record RoleBody(
            @Schema(description = "The role's code, exactly as it appears in "
                                  + "`GET /api/v1/admin/roles`.",
                    example = "AUDITOR", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 48) String roleCode,

            @Schema(description = "Limits the grant to one company: `JM` (Joshua Meuzelaar LLC) or "
                                  + "`BP` (Bipolarbear Enterprises). Leave it out — or send null — "
                                  + "and the grant covers every company.",
                    example = "JM", allowableValues = {"JM", "BP"})
            @Pattern(regexp = "JM|BP", message = "must be JM or BP")
            String entity) {
    }

    @Schema(description = "A new account. The administrator sets the first password.")
    public record CreateUserBody(
            @Schema(description = "Login name. Lowercase letters, digits, dot, underscore or dash; "
                                  + "3–64 characters; must start with a letter or digit. Must be "
                                  + "unique — a duplicate comes back as 409.",
                    example = "j.karimov", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(min = 3, max = 64)
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{2,63}$",
                     message = "lowercase letters, digits, dot, underscore or dash")
            String username,

            @Schema(description = "The person's full name, as it should appear in the UI.",
                    example = "Jasur Karimov", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 160) String fullName,

            @Schema(description = "Optional contact address.", example = "j.karimov@drenix.uz")
            @Size(max = 160) String email,

            @Schema(description = "Optional contact number.", example = "+998901234567")
            @Size(max = 32) String phone,

            @Schema(description = "The RingCentral extension or number this person's calls and SMS "
                                  + "are logged against. Kept separate from `phone`: that one is how "
                                  + "a colleague reaches them, this one is what the call metrics key "
                                  + "on. Must be unique across accounts.",
                    example = "+13055551234")
            @Size(max = 32) @Pattern(regexp = "^[+0-9][0-9 ()x.-]{2,31}$",
                                     message = "digits, optionally with +, spaces, brackets, dots, "
                                               + "dashes or an x extension")
            String ringCentralPhone,

            @Schema(description = "Every RingCentral extension or number this person's calls and "
                                  + "SMS are logged against. A lead who works two extensions puts "
                                  + "both here and the activity report adds them into one row. "
                                  + "Each must be unique across accounts. Prefer this over the "
                                  + "single `ringCentralPhone`, which is only kept for older "
                                  + "clients and is ignored when this list is present.",
                    example = "[\"+13055551234\", \"+13125559876\"]")
            @Size(max = 10) List<@Pattern(regexp = "^[+0-9][0-9 ()x.-]{2,31}$",
                                          message = "digits, optionally with +, spaces, brackets, "
                                                    + "dots, dashes or an x extension")
                                 @Size(max = 32) String> ringCentralPhones,

            @Schema(description = "The name the monday.com recruiting board uses for this person, "
                                  + "in its Source column. The recruiting chart is keyed on it — "
                                  + "leave it out and this account appears in no chart. Board names "
                                  + "are often short forms, so it is rarely the same as fullName.",
                    example = "Alex")
            @Size(max = 64) String mondayName,

            @Schema(description = "The day this person started with the company, `YYYY-MM-DD`. "
                                  + "Optional, and worth filling in: it decides which quarter of "
                                  + "their own tenure the recruiting standard measures them "
                                  + "against. Left out, tenure is inferred from their first hire "
                                  + "on the board, which can only ever be **later** than the "
                                  + "truth — a lead of two years came out at fifteen months, and "
                                  + "a recruiter in his fourth month at his fifth.",
                    example = "2026-06-01")
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}",
                     message = "employmentStartDate must look like 2026-06-01")
            String employmentStartDate,

            @Schema(description = "The password the person signs in with the first time. At least "
                                  + "12 characters, and it must not contain their username or "
                                  + "name. The account is always created with "
                                  + "`mustChangePassword: true`, so this value survives exactly "
                                  + "one login.",
                    example = "Qorbobo7Zilzila", minLength = 12, maxLength = 256,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(min = 12, max = 256) String initialPassword,

            @Schema(description = "Roles to grant right away. Optional — an account with no roles "
                                  + "can sign in and change its own password, nothing more.")
            List<@Valid RoleBody> roles) {
    }

    @Schema(description = """
            Only the fields you send are written. Omit a field and it keeps its current value; \
            there is no way to blank a field by leaving it out.""")
    public record UpdateUserBody(
            @Schema(description = "New full name.", example = "Jasur Karimov")
            @Size(max = 160) String fullName,

            @Schema(description = "New email address.", example = "jasur@drenix.uz")
            @Size(max = 160) String email,

            @Schema(description = "New phone number.", example = "+998901112233")
            @Size(max = 32) String phone,

            @Schema(description = "New RingCentral extension or number. Must be unique across "
                                  + "accounts — a duplicate comes back as 409.",
                    example = "+13055551234")
            @Size(max = 32) @Pattern(regexp = "^[+0-9][0-9 ()x.-]{2,31}$",
                                     message = "digits, optionally with +, spaces, brackets, dots, "
                                               + "dashes or an x extension")
            String ringCentralPhone,

            @Schema(description = "Replaces the whole list of RingCentral numbers — send every one "
                                  + "the person keeps, not just the new one. An empty array clears "
                                  + "them. Omit the field to leave them alone. Each must be unique "
                                  + "across accounts; a duplicate is a 409.",
                    example = "[\"+13055551234\", \"+13125559876\"]")
            @Size(max = 10) List<@Pattern(regexp = "^[+0-9][0-9 ()x.-]{2,31}$",
                                          message = "digits, optionally with +, spaces, brackets, "
                                                    + "dots, dashes or an x extension")
                                 @Size(max = 32) String> ringCentralPhones,

            @Schema(description = "New monday.com board name. Must be unique across accounts.",
                    example = "Alex")
            @Size(max = 64) String mondayName,

            @Schema(description = "The day this person started, `YYYY-MM-DD`. Send `\"\"` to clear "
                                  + "it and go back to tenure inferred from their first hire.",
                    example = "2026-06-01")
            @Pattern(regexp = "|\\d{4}-\\d{2}-\\d{2}",
                     message = "employmentStartDate must look like 2026-06-01")
            String employmentStartDate,

            // The three states the proto actually defines. Anything else used to reach
            // UserStatus.valueOf and surface as a 500 for what is a caller mistake.
            @Schema(description = """
                    `ACTIVE` — can sign in. `DISABLED` — switched off by an administrator; login \
                    returns the same 401 as a wrong password. `LOCKED` — too many failed attempts; \
                    normally clears itself after the lock window, but you can set it by hand.""",
                    example = "DISABLED", allowableValues = {"ACTIVE", "DISABLED", "LOCKED"})
            @Pattern(regexp = "ACTIVE|DISABLED|LOCKED",
                     message = "must be ACTIVE, DISABLED or LOCKED")
            String status) {
    }

    @Schema(description = "An administrator-issued password for somebody else's account.")
    public record ResetPasswordBody(
            @Schema(description = "At least 12 characters, and it must not contain the account's "
                                  + "username or name. The account is forced to change it at the "
                                  + "next login.",
                    example = "Anhor5Chinniqand", minLength = 12, maxLength = 256,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(min = 12, max = 256) String newPassword) {
    }

    @Schema(description = "Why the account is being removed. Required — it goes into the audit log.")
    public record DeleteBody(
            @Schema(description = "A human explanation, kept in the audit trail forever. Optional "
                                  + "here only because `?reason=` is the other way to send it; one "
                                  + "of the two is required.",
                    example = "Left the company on 2026-08-01")
            @Size(max = 500) String reason) {
    }

    @Schema(description = "Confirmation that the account is gone.")
    public record DeletedBody(
            @Schema(example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338") String id,
            @Schema(example = "true") boolean deleted) {
    }

    private final UserServiceGrpc.UserServiceBlockingStub userService;
    private final AuthServiceGrpc.AuthServiceBlockingStub authService;

    public AdminUserController(UserServiceGrpc.UserServiceBlockingStub userService,
                               AuthServiceGrpc.AuthServiceBlockingStub authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @Operation(
            summary = "Create a user account",
            description = """
                    Creates an account and optionally grants it roles in the same call. Needs the \
                    `user:create` permission.

                    This is the only way an account comes into existence — there is no registration \
                    endpoint anywhere in the system.

                    The account always starts with `mustChangePassword: true`, so the \
                    `initialPassword` you set here works for exactly one login. Hand it over on a \
                    channel you trust.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Created. The full account is returned."),
            @ApiResponse(responseCode = "400",
                    description = "A field fails validation, or the password fails the policy — it "
                                  + "is under 12 characters, or contains the username or name.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `user:create`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "That username is already taken.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PostMapping
    @PreAuthorize("hasAuthority('user:create')")
    public ResponseEntity<Views.UserView> create(@Valid @RequestBody CreateUserBody body) {
        CreateUserRequest.Builder grpcRequest = CreateUserRequest.newBuilder()
                .setUsername(body.username())
                .setFullName(body.fullName())
                .setEmail(nullToEmpty(body.email()))
                .setPhone(nullToEmpty(body.phone()))
                .setRingCentralPhone(nullToEmpty(body.ringCentralPhone()))
                .addAllRingCentralPhones(
                        body.ringCentralPhones() == null ? List.of() : body.ringCentralPhones())
                .setMondayName(nullToEmpty(body.mondayName()))
                .setEmploymentStartDate(nullToEmpty(body.employmentStartDate()))
                .setInitialPassword(body.initialPassword());
        toAssignments(body.roles()).forEach(grpcRequest::addRoles);

        return ResponseEntity.ok(Views.of(userService.createUser(grpcRequest.build())));
    }

    @Operation(
            summary = "List users",
            description = """
                    Returns one page of accounts, newest first. Needs `user:read`.

                    Paging is cursor-based, not offset-based: take `nextCursor` from the response, \
                    pass it back as `cursor` for the next page, and stop when `hasMore` is false. \
                    Offsets skip or repeat rows while accounts are being created; cursors do not.

                    Leave every parameter empty for the first page of 50.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of accounts."),
            @ApiResponse(responseCode = "400", description = "`limit` is outside 1–200.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `user:read`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    public Views.PageView<Views.UserView> list(
            @Parameter(description = "Free-text filter over username and full name. Leave empty to "
                                     + "list everyone.",
                       example = "karimov")
            @RequestParam(required = false) @Size(max = 128) String search,

            @Parameter(description = "The `nextCursor` from the previous page. Leave empty for the "
                                     + "first page. It is opaque — do not build one by hand.")
            @RequestParam(required = false) @Size(max = 512) String cursor,

            @Parameter(description = "How many accounts to return, 1–200.", example = "50")
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return Views.of(userService.listUsers(ListUsersRequest.newBuilder()
                .setPage(PageRequest.newBuilder()
                        .setSearch(nullToEmpty(search))
                        .setCursor(nullToEmpty(cursor))
                        .setLimit(limit)
                        .build())
                .build()));
    }

    @Operation(
            summary = "Get one user",
            description = "Returns a single account with its roles and its effective permissions "
                          + "(the union of every role it holds). Needs `user:read`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The account."),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `user:read`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account with that id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:read')")
    public Views.UserView get(
            @Parameter(description = "The account's UUID, from a list or create response.",
                       example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338", required = true)
            @PathVariable String id) {
        return Views.of(userService.getUser(GetUserRequest.newBuilder().setId(id).build()));
    }

    @Operation(
            summary = "Update a user",
            description = """
                    Edits profile fields and status. Needs `user:update`.

                    Send only what you want to change — a field you leave out keeps its current \
                    value. There is no way to blank a field by omitting it.

                    Setting `status` to `DISABLED` stops the account signing in, but its existing \
                    sessions live until their tokens expire. To end those now, reset the password.

                    Roles are not editable here — use `PUT /api/v1/admin/users/{id}/roles`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated. The full account is returned."),
            @ApiResponse(responseCode = "400",
                    description = "A field fails validation, or `status` is not ACTIVE, DISABLED "
                                  + "or LOCKED.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `user:update`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account with that id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('user:update')")
    public Views.UserView update(
            @Parameter(description = "The account's UUID.",
                       example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338", required = true)
            @PathVariable String id,
            @Valid @RequestBody UpdateUserBody body) {
        UpdateUserRequest.Builder request = UpdateUserRequest.newBuilder().setId(id);

        // The mask is built from what the caller actually sent, so a PATCH that omits a field
        // leaves it alone instead of blanking it.
        if (body.fullName() != null) {
            request.addUpdateMask("full_name").setFullName(body.fullName());
        }
        if (body.email() != null) {
            request.addUpdateMask("email").setEmail(body.email());
        }
        if (body.phone() != null) {
            request.addUpdateMask("phone").setPhone(body.phone());
        }
        if (body.ringCentralPhone() != null) {
            request.addUpdateMask("ring_central_phones")
                    .setRingCentralPhone(body.ringCentralPhone());
        }
        // The list wins when both are sent: user-service folds the singular one in only when the
        // repeated field is empty, so an older client and a newer one cannot disagree.
        if (body.ringCentralPhones() != null) {
            request.addUpdateMask("ring_central_phones")
                    // Clear the shorthand too: user-service falls back to it when the list is
                    // empty, which would quietly defeat an empty array sent to clear the numbers.
                    .setRingCentralPhone("")
                    .clearRingCentralPhones()
                    .addAllRingCentralPhones(body.ringCentralPhones());
        }
        if (body.mondayName() != null) {
            request.addUpdateMask("monday_name").setMondayName(body.mondayName());
        }
        if (body.employmentStartDate() != null) {
            request.addUpdateMask("employment_start_date")
                    .setEmploymentStartDate(body.employmentStartDate());
        }
        if (body.status() != null) {
            request.addUpdateMask("status").setStatus(UserStatus.valueOf("USER_STATUS_" + body.status()));
        }
        return Views.of(userService.updateUser(request.build()));
    }

    @Operation(
            summary = "Delete a user",
            description = """
                    Soft-deletes the account and revokes its sessions. Needs `user:delete`.

                    The row is kept so the audit log still resolves who did what; the account simply \
                    stops existing as far as this API is concerned, and its username is not freed \
                    for reuse.

                    ## Sending the reason

                    `reason` goes into the audit log and is required. Send it **either** way:

                    ```
                    DELETE /api/v1/admin/users/{id}?reason=Left%20the%20company
                    DELETE /api/v1/admin/users/{id}   {"reason":"Left the company"}
                    ```

                    The query parameter exists because a body on a DELETE is a trap: `fetch` \
                    forbids one outright, and several HTTP clients drop it silently. When that \
                    happened this endpoint answered 400 and the delete did not happen, which is \
                    indistinguishable from a server fault at the other end. The query form always \
                    arrives.

                    Answers **200** with a small JSON body rather than an empty 204, so a client \
                    that parses every response as JSON does not throw on a call that in fact \
                    succeeded.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deleted."),
            @ApiResponse(responseCode = "400",
                    description = "`reason` was sent neither as a query parameter nor in a body.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `user:delete`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account with that id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('user:delete')")
    public DeletedBody delete(
            @Parameter(description = "The account's UUID.",
                       example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338", required = true)
            @PathVariable String id,

            @Parameter(description = "Why, for the audit log. Use this or the body, not both.",
                       example = "Left the company on 2026-08-01")
            @RequestParam(required = false) @Size(max = 500) String reason,

            @RequestBody(required = false) DeleteBody body) {

        userService.deleteUser(DeleteUserRequest.newBuilder()
                .setId(id)
                .setReason(reasonFrom(reason, body == null ? null : body.reason()))
                .build());
        return new DeletedBody(id, true);
    }

    /**
     * The reason, from wherever the client managed to send it.
     *
     * <p>Validated here rather than with {@code @NotBlank} on the body, because the body is now
     * optional and a bean-validation annotation cannot express "one of these two".
     */
    private static String reasonFrom(String query, String body) {
        String reason = query != null && !query.isBlank() ? query : body;
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(
                    "reason is required: send ?reason=... or a body of {\"reason\":\"...\"}");
        }
        return reason.strip();
    }

    @Operation(
            summary = "Replace a user's roles",
            description = """
                    Sets the account's roles to exactly the list you send. Needs `user:assignRole`.

                    **This replaces, it does not add.** Send the complete set every time; an empty \
                    array `[]` strips every role.

                    The body is a bare JSON array, not an object. Each entry needs `roleCode`, and \
                    may carry `entity` to narrow the grant to one company.

                    A change reaches the person within one access-token lifetime (10 minutes), or \
                    immediately on their next refresh.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Roles replaced. The account is returned."),
            @ApiResponse(responseCode = "400",
                    description = "An unknown role code, or an `entity` that is not JM or BP.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `user:assignRole`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account with that id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:assignRole')")
    public Views.UserView assignRoles(
            @Parameter(description = "The account's UUID.",
                       example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338", required = true)
            @PathVariable String id,
            @RequestBody List<@Valid RoleBody> roles) {
        AssignRolesRequest.Builder request = AssignRolesRequest.newBuilder().setUserId(id);
        toAssignments(roles).forEach(request::addRoles);
        return Views.of(userService.assignRoles(request.build()));
    }

    @Operation(
            summary = "Reset another user's password",
            description = """
                    Sets a new password on somebody else's account. Needs `user:resetPassword`.

                    For your own password use `POST /api/v1/auth/password` instead — that endpoint \
                    verifies the current password, this one does not, which is the whole point of \
                    it being a separate permission.

                    Every session of the target account is revoked, and the account must choose a \
                    new password at its next login.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password reset. No body."),
            @ApiResponse(responseCode = "400", description = "The password fails the policy.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `user:resetPassword`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account with that id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/{id}/password")
    @PreAuthorize("hasAuthority('user:resetPassword')")
    public ResponseEntity<Void> resetPassword(
            @Parameter(description = "The account's UUID.",
                       example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338", required = true)
            @PathVariable String id,
            @Valid @RequestBody ResetPasswordBody body) {
        userService.resetPassword(ResetPasswordRequest.newBuilder()
                .setUserId(id)
                .setNewPassword(body.newPassword())
                .build());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Clear a lockout",
            description = """
                    Undoes a lockout caused by failed logins, without changing the password. Needs \
                    `user:update`.

                    Five wrong passwords lock an account for fifteen minutes. Until now the only \
                    way to end that early was to reset the password, which punishes the person who \
                    was guessed at rather than the guesser. This clears the failed-attempt counter \
                    and the lock, and returns a `LOCKED` account to `ACTIVE`.

                    A `DISABLED` account stays disabled — that state was chosen by a person, and \
                    unlocking must not quietly undo it. Use `PATCH /api/v1/admin/users/{id}` for \
                    that.

                    Recorded in the audit trail either way, including when the account turned out \
                    not to be locked: someone asking for an unlock is worth knowing about.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lockout cleared. The account is returned."),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `user:update`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account with that id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('user:update')")
    public Views.UserView unlock(
            @Parameter(description = "The account's UUID.",
                       example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338", required = true)
            @PathVariable String id) {
        return Views.of(userService.unlockUser(UnlockUserRequest.newBuilder().setId(id).build()));
    }

    @Operation(
            summary = "List a user's active sessions",
            description = """
                    Every device or client currently signed in as this account. Needs \
                    `session:revoke`.

                    Each entry shows where the session was opened from and when. An address or a \
                    client the person does not recognise is the signal that the account is \
                    compromised — and the revoke endpoint below is the answer.

                    Sessions disappear from this list on their own when their refresh window ends \
                    (14 days by default).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "The account's live sessions, newest first. Empty when it is "
                                  + "signed out everywhere."),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `session:revoke`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @GetMapping("/{id}/sessions")
    @PreAuthorize("hasAuthority('session:revoke')")
    public List<Views.SessionView> sessions(
            @Parameter(description = "The account's UUID.",
                       example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338", required = true)
            @PathVariable String id) {
        return Views.of(authService.listSessions(
                ListSessionsRequest.newBuilder().setUserId(id).build()));
    }

    @Operation(
            summary = "Revoke sessions",
            description = """
                    Signs an account out. Needs `session:revoke`.

                    Without `sessionId` every session ends and the refresh-token families die with \
                    them, so nothing can mint a replacement. With `sessionId` only that one \
                    session goes — the right choice when someone lost one device but is still \
                    working on another.

                    Revocation is immediate, not "when the token expires": the sessions go on a \
                    denylist that the gateway checks on every single request. This is the endpoint \
                    to reach for the moment an account is believed compromised, ahead of resetting \
                    the password.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Revoked. No body."),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "403", description = "The token lacks `session:revoke`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @DeleteMapping("/{id}/sessions")
    @PreAuthorize("hasAuthority('session:revoke')")
    public ResponseEntity<Void> revokeSessions(
            @Parameter(description = "The account's UUID.",
                       example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338", required = true)
            @PathVariable String id,

            @Parameter(description = "One session to end, from the list endpoint. Leave it out to "
                                     + "end every session the account has.",
                       example = "aae76810-997a-4412-b4a5-637e3a21e6eb")
            @RequestParam(required = false) @Size(max = 64) String sessionId) {

        authService.revokeSessions(RevokeSessionsRequest.newBuilder()
                .setUserId(id)
                .setSessionId(nullToEmpty(sessionId))
                .build());
        return ResponseEntity.noContent().build();
    }

    private static List<RoleAssignment> toAssignments(List<RoleBody> roles) {
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(role -> RoleAssignment.newBuilder()
                        .setRoleCode(role.roleCode())
                        .setEntity(toEntity(role.entity()))
                        .build())
                .toList();
    }

    /**
     * An unrecognised name is a client mistake, so it has to surface as a 400. A bare
     * {@code Entity.valueOf} would throw IllegalArgumentException with an enum-internals message.
     */
    private static Entity toEntity(String name) {
        if (name == null || name.isBlank()) {
            // No entity means the grant is global rather than narrowed to one company.
            return Entity.ENTITY_UNSPECIFIED;
        }
        Entity entity = Entity.valueOf("ENTITY_" + name.trim().toUpperCase(java.util.Locale.ROOT));
        if (entity == Entity.UNRECOGNIZED) {
            throw new BadRequestException("Unknown entity: " + name);
        }
        return entity;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
