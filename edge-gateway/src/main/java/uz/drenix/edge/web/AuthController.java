package uz.drenix.edge.web;

import io.grpc.StatusRuntimeException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.drenix.identity.grpc.v1.AuthServiceGrpc;
import uz.drenix.identity.grpc.v1.ChangeOwnPasswordRequest;
import uz.drenix.identity.grpc.v1.Entity;
import uz.drenix.identity.grpc.v1.LoginRequest;
import uz.drenix.identity.grpc.v1.LoginResponse;
import uz.drenix.identity.grpc.v1.LogoutRequest;
import uz.drenix.identity.grpc.v1.RefreshRequest;
import uz.drenix.identity.grpc.v1.RefreshResponse;
import uz.drenix.identity.grpc.v1.RoleAssignment;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;
import uz.drenix.platform.security.AuthPrincipal;
import uz.drenix.platform.security.Permissions;

/**
 * The public authentication API.
 *
 * <p>Note what is absent: there is no registration endpoint, and there never will be. Accounts
 * come from an administrator calling {@code POST /api/v1/admin/users}.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Schema(description = "Username and password. Both are required.")
    public record LoginBody(
            @Schema(description = "The account name. Lowercase, 3–64 characters.",
                    example = "admin", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 64) String username,

            @Schema(description = "The account password, up to 256 characters.",
                    example = "ChangeMeAfterFirstLogin", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 256) String password) {
    }

    @Schema(description = "A freshly issued token pair, plus who the tokens belong to.")
    public record TokenBody(
            @Schema(description = "Send this as `Authorization: Bearer <accessToken>`. Valid for "
                                  + "10 minutes.",
                    example = "eyJraWQiOiJiYzk3ZTQ1NTVlODE5MDhjIiwidHlwIjoiYXQrand0In0...")
            String accessToken,

            @Schema(description = "Opaque, single use. Spend it on `POST /api/v1/auth/refresh` to "
                                  + "get the next pair, then discard it. Reusing one revokes every "
                                  + "session the account has.",
                    example = "SanNFHqJfryX_OzfhmzzLVHMkCRItL_dz0iBGnSMZSA")
            String refreshToken,

            @Schema(description = "When the access token stops working (UTC).",
                    example = "2026-08-25T14:59:38Z")
            Instant accessExpiresAt,

            @Schema(description = "When the refresh token stops working (UTC). 14 days by default.",
                    example = "2026-09-08T14:49:38Z")
            Instant refreshExpiresAt,

            @Schema(description = "True when the account must set a new password before it can do "
                                  + "anything else. The token above then carries only the "
                                  + "`auth:changeOwnPassword` permission.",
                    example = "true")
            boolean passwordChangeRequired,

            @Schema(description = "The signed-in account. Null on a refresh response — refreshing "
                                  + "renews tokens, it does not re-read the profile.")
            UserBody user) {
    }

    @Schema(description = "The signed-in account, as much of it as the client needs.")
    public record UserBody(
            @Schema(description = "Stable identifier (UUID). Use it in the admin endpoints.",
                    example = "2ddec19c-a8f4-4ef2-8ddc-0ac71757c338")
            String id,

            @Schema(example = "admin") String username,

            @Schema(example = "Bootstrap administrator") String fullName,

            @Schema(description = "What the access token above actually grants — the same list is "
                                  + "inside it. While `passwordChangeRequired` is true this is "
                                  + "only `auth:changeOwnPassword`, not the account's full set; "
                                  + "sign in again after changing the password to receive the rest.",
                    example = "[\"user:read\",\"user:create\",\"auth:changeOwnPassword\"]")
            List<String> permissions,

            @Schema(description = "The roles this account holds. `entity` is null on a grant that "
                                  + "covers every company.")
            List<RoleGrantBody> roles,

            @Schema(description = "**The companies this account may act on, spelled out.** Build "
                                  + "the company switcher from this and nothing else.\n\n"
                                  + "A role granted with no `entity` covers every company, and "
                                  + "this field has already resolved that: an `OWNER` comes back "
                                  + "as `[\"JM\",\"BP\"]`, not as an empty list. Reading the "
                                  + "companies off `roles` instead means reimplementing that rule "
                                  + "in the client, and getting it wrong shows up as an owner "
                                  + "being told they have no access to BP.",
                    example = "[\"JM\",\"BP\"]")
            List<String> companies) {
    }

    @Schema(description = "One role the account holds, and the company it is narrowed to.")
    public record RoleGrantBody(
            @Schema(example = "HR_LEAD") String roleCode,
            @Schema(description = "Null means the role covers every company.", example = "JM",
                    nullable = true) String entity) {
    }

    @Schema(description = "The refresh token you were last given.")
    public record RefreshBody(
            @Schema(description = "The `refreshToken` from your most recent login or refresh. Each "
                                  + "one works exactly once.",
                    example = "SanNFHqJfryX_OzfhmzzLVHMkCRItL_dz0iBGnSMZSA",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String refreshToken) {
    }

    @Schema(description = "Your current password and the one you want instead.")
    public record ChangePasswordBody(
            @Schema(description = "The password you are signing in with right now.",
                    example = "ChangeMeAfterFirstLogin", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 256) String currentPassword,

            @Schema(description = "At least 12 characters. It must not contain your username or "
                                  + "your name, and it is checked against a list of common "
                                  + "passwords — both are rejected with 400.",
                    example = "Qorbobo7Zilzila", minLength = 12, maxLength = 256,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(min = 12, max = 256) String newPassword) {
    }

    private final AuthServiceGrpc.AuthServiceBlockingStub authService;
    private final UserServiceGrpc.UserServiceBlockingStub userService;

    public AuthController(AuthServiceGrpc.AuthServiceBlockingStub authService,
                          UserServiceGrpc.UserServiceBlockingStub userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @Operation(
            summary = "Sign in",
            description = """
                    Exchanges a username and password for a token pair. No token needed to call it.

                    **Start here.** Copy `accessToken` from the response into the **Authorize** \
                    button at the top of this page, and every other endpoint will work.

                    If the response says `passwordChangeRequired: true`, the token you just \
                    received can do exactly one thing: `POST /api/v1/auth/password`. Everything \
                    else answers 403 until the password is changed. The first administrator always \
                    starts in that state.

                    Failures are deliberately indistinguishable. A wrong password, an unknown \
                    username, a locked account and a disabled account all return the same 401 with \
                    the same message — anything else would confirm which usernames exist.""")
    // An empty @SecurityRequirements is what marks an operation as public. Writing
    // @Operation(security = {}) instead looks equivalent but is not: swagger-core reads an empty
    // array there as "nothing specified", so the document-level bearer requirement survives and
    // the endpoint is documented as needing a token it never reads.
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in. Token pair returned.",
                    content = @Content(schema = @Schema(implementation = TokenBody.class))),
            @ApiResponse(responseCode = "400",
                    description = "The body is missing a field or is not valid JSON.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "Wrong credentials, or the account is locked or disabled.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429",
                    description = "Too many attempts. A `Retry-After` header says how many seconds "
                                  + "to wait. The limit counts per username and per IP.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503",
                    description = "auth-service is unreachable.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginBody body, HttpServletRequest request) {
        LoginResponse response = authService.login(LoginRequest.newBuilder()
                .setUsername(body.username())
                .setPassword(body.password())
                .setClientIp(ClientIp.of(request))
                .setUserAgent(header(request, "User-Agent"))
                .build());

        return switch (response.getOutcome()) {
            case OUTCOME_SUCCESS, OUTCOME_PASSWORD_CHANGE_REQUIRED ->
                    ResponseEntity.ok(toBody(response));
            case OUTCOME_RATE_LIMITED ->
                    ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                            // auth-service says when the window reopens; without passing it on, a
                            // client can only guess, and guessing means retrying into the limit.
                            .headers(headers -> retryAfterSeconds(response)
                                    .ifPresent(seconds -> headers.add("Retry-After", Long.toString(seconds))))
                            .body(ApiError.of("too_many_attempts", "Too many attempts. Try again later."));
            case OUTCOME_DISABLED, OUTCOME_LOCKED, OUTCOME_INVALID_CREDENTIALS, OUTCOME_UNSPECIFIED, UNRECOGNIZED ->
                    // One message for every failure. Distinguishing "locked" from "wrong password"
                    // confirms which usernames exist.
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(ApiError.of("invalid_credentials", "Username or password is incorrect."));
        };
    }

    @Operation(
            summary = "Renew an expired access token",
            description = """
                    Trades a refresh token for a fresh pair. No access token needed — the refresh \
                    token is the credential.

                    Call this when a request comes back 401 because the access token aged out \
                    (they last 10 minutes).

                    **Each refresh token works once.** The response contains a new one; keep it and \
                    throw the old one away. Sending the same refresh token a second time is read as \
                    theft — one of the two holders must be an attacker — and every session for that \
                    account is revoked on the spot. The honest client then has to sign in again.

                    Permissions are re-read from user-service on every refresh, so a role change \
                    reaches the client within one access-token lifetime rather than waiting out the \
                    14-day refresh window.""")
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "New token pair. `user` is null here — refreshing renews tokens, "
                                  + "it does not re-read the profile.",
                    content = @Content(schema = @Schema(implementation = TokenBody.class))),
            @ApiResponse(responseCode = "400", description = "`refreshToken` is missing or blank.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401",
                    description = "Unknown, expired, already-used or revoked refresh token. Sign in "
                                  + "again.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshBody body, HttpServletRequest request) {
        RefreshResponse response = authService.refresh(RefreshRequest.newBuilder()
                .setRefreshToken(body.refreshToken())
                .setClientIp(ClientIp.of(request))
                .setUserAgent(header(request, "User-Agent"))
                .build());

        if (response.getOutcome() != RefreshResponse.Outcome.OUTCOME_SUCCESS) {
            // Reuse detection already revoked everything server-side; the client just gets 401
            // and has to log in again.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiError.of("invalid_refresh_token", "Please sign in again."));
        }

        return ResponseEntity.ok(new TokenBody(
                response.getTokens().getAccessToken(),
                response.getTokens().getRefreshToken(),
                toInstant(response.getTokens().getAccessExpiresAt().getSeconds()),
                toInstant(response.getTokens().getRefreshExpiresAt().getSeconds()),
                false,
                null));
    }

    @Operation(
            summary = "Sign out of this session",
            description = """
                    Closes the session the access token belongs to and puts it on the revocation \
                    denylist, so the token stops working immediately rather than at its expiry. \
                    Other sessions of the same account are left alone — to end all of them, change \
                    the password.

                    Takes no body. Send the access token and nothing else.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Signed out. No body."),
            @ApiResponse(responseCode = "401", description = "Missing, expired or revoked token.")})
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthPrincipal principal) {
        authService.logout(LogoutRequest.newBuilder()
                .setSessionId(principal.sessionId())
                .setUserId(principal.userId().toString())
                .build());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Change your own password",
            description = """
                    Changes the password of the signed-in account. Needs the \
                    `auth:changeOwnPassword` permission, which every account holds — including one \
                    that is still in the forced-change state, so this is the way out of it.

                    An administrator resetting *somebody else's* password uses \
                    `POST /api/v1/admin/users/{id}/password` instead.

                    **Every session dies, including this one.** After a 204 your access token is \
                    dead: sign in again with the new password. A password change that leaves the \
                    attacker's session alive has not actually changed anything.

                    The new password must be at least 12 characters, must not contain your username \
                    or your name, and must not be a commonly used password. Any of those comes back \
                    as 400 with a message naming the rule.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204",
                    description = "Password changed and all sessions revoked. No body."),
            @ApiResponse(responseCode = "400",
                    description = "The new password fails the policy, or `currentPassword` is wrong.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing, expired or revoked token."),
            @ApiResponse(responseCode = "403",
                    description = "The token lacks `auth:changeOwnPassword`.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordBody body,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        userService.changeOwnPassword(ChangeOwnPasswordRequest.newBuilder()
                .setUserId(principal.userId().toString())
                .setCurrentPassword(body.currentPassword())
                .setNewPassword(body.newPassword())
                .build());

        // Every existing session dies with the old password, including this one. A password
        // change that leaves the attacker's session alive has not actually changed anything.
        //
        // The password is already changed by this point, so a failure here must not be reported
        // as a failed password change: the caller would retry with a current password that is no
        // longer current and get a confusing rejection. It is logged loudly instead — stale
        // sessions surviving a password change is an incident, not a request-level error.
        try {
            authService.logout(LogoutRequest.newBuilder()
                    .setUserId(principal.userId().toString())
                    .setAllSessions(true)
                    .build());
        } catch (StatusRuntimeException e) {
            log.error("Password changed for user {} but its sessions could not be revoked",
                    principal.userId(), e);
        }
        return ResponseEntity.noContent().build();
    }

    /** Whole seconds until the rate-limit window reopens, absent when auth-service did not say. */
    private static OptionalLong retryAfterSeconds(LoginResponse response) {
        long epochSeconds = response.getRetryAfter().getSeconds();
        if (epochSeconds == 0) {
            return OptionalLong.empty();
        }
        long seconds = epochSeconds - Instant.now().getEpochSecond();
        return seconds > 0 ? OptionalLong.of(seconds) : OptionalLong.empty();
    }

    private static TokenBody toBody(LoginResponse response) {
        boolean passwordChangeRequired =
                response.getOutcome() == LoginResponse.Outcome.OUTCOME_PASSWORD_CHANGE_REQUIRED;

        // What the token can actually do, not what the account will be able to do once its
        // password is set. auth-service narrows a forced-change token to one permission, but the
        // User message still carries the account's full set — reporting that here told the client
        // it held thirteen permissions while every call but one came back 403.
        List<String> permissions = passwordChangeRequired
                ? List.of(Permissions.AUTH_CHANGE_OWN_PASSWORD)
                : List.copyOf(response.getUser().getPermissionsList());

        List<RoleGrantBody> roles = response.getUser().getRolesList().stream()
                .map(role -> new RoleGrantBody(
                        role.getRoleCode(),
                        role.getEntity() == Entity.ENTITY_UNSPECIFIED
                                ? null
                                : shortName(role.getEntity())))
                .toList();

        return new TokenBody(
                response.getTokens().getAccessToken(),
                response.getTokens().getRefreshToken(),
                toInstant(response.getTokens().getAccessExpiresAt().getSeconds()),
                toInstant(response.getTokens().getRefreshExpiresAt().getSeconds()),
                passwordChangeRequired,
                new UserBody(
                        response.getUser().getId(),
                        response.getUser().getUsername(),
                        response.getUser().getFullName(),
                        permissions,
                        roles,
                        companiesOf(response.getUser().getRolesList())));
    }

    /**
     * The companies this account may act on, with the "no entity means all of them" rule already
     * applied.
     *
     * <p>Resolved here rather than left to the client. The rule lives in three places on the
     * server — the token's {@code ent} claim, {@code AuthPrincipal.canActOn} and every controller
     * that scopes by company — and a client that has to reimplement it will eventually get it
     * wrong in exactly one direction: an unscoped grant looks like <em>no</em> companies rather
     * than every company, and the owner of the business is told they have no access to BP while
     * every endpoint happily serves them.
     */
    private static List<String> companiesOf(List<RoleAssignment> assignments) {
        for (RoleAssignment assignment : assignments) {
            if (assignment.getEntity() == Entity.ENTITY_UNSPECIFIED) {
                return ALL_COMPANIES;
            }
        }
        return assignments.stream()
                .map(assignment -> shortName(assignment.getEntity()))
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Every company that actually operates.
     *
     * <p>Taken from the enum rather than written out, so a third company added to the contract
     * appears here without anybody remembering to. UNSPECIFIED is not a company and UNRECOGNIZED
     * is a value this build does not know.
     */
    private static final List<String> ALL_COMPANIES = java.util.Arrays.stream(Entity.values())
            .filter(entity -> entity != Entity.ENTITY_UNSPECIFIED && entity != Entity.UNRECOGNIZED)
            .map(AuthController::shortName)
            .sorted()
            .toList();

    private static String shortName(Entity entity) {
        return entity.name().replace("ENTITY_", "");
    }

    private static Instant toInstant(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds);
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? "" : value.substring(0, Math.min(value.length(), 256));
    }
}
