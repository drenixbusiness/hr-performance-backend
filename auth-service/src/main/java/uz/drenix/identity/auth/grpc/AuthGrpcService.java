package uz.drenix.identity.auth.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.drenix.identity.auth.session.LoginRateLimiter;
import uz.drenix.identity.auth.session.RefreshTokenStore;
import uz.drenix.identity.auth.session.SessionRegistry;
import uz.drenix.identity.auth.token.AccessTokenIssuer;
import uz.drenix.identity.grpc.v1.AuthEmpty;
import uz.drenix.identity.grpc.v1.AuthServiceGrpc;
import uz.drenix.identity.grpc.v1.Entity;
import uz.drenix.identity.grpc.v1.LoginRequest;
import uz.drenix.identity.grpc.v1.LoginResponse;
import uz.drenix.identity.grpc.v1.ListSessionsRequest;
import uz.drenix.identity.grpc.v1.ListSessionsResponse;
import uz.drenix.identity.grpc.v1.LogoutRequest;
import uz.drenix.identity.grpc.v1.RefreshRequest;
import uz.drenix.identity.grpc.v1.RefreshResponse;
import uz.drenix.identity.grpc.v1.RevokeSessionsRequest;
import uz.drenix.identity.grpc.v1.RoleAssignment;
import uz.drenix.identity.grpc.v1.SessionInfo;
import uz.drenix.identity.grpc.v1.TokenPair;
import uz.drenix.identity.grpc.v1.User;
import uz.drenix.identity.grpc.v1.UserServiceGrpc;
import uz.drenix.identity.grpc.v1.VerifyCredentialsRequest;
import uz.drenix.identity.grpc.v1.VerifyCredentialsResponse;
import uz.drenix.platform.security.Permissions;

/**
 * The authentication flow. auth-service never sees a password hash: it forwards the candidate
 * password to user-service over mTLS and acts on the verdict.
 */
@Service
public class AuthGrpcService extends AuthServiceGrpc.AuthServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(AuthGrpcService.class);

    private final UserServiceGrpc.UserServiceBlockingStub userService;
    private final AccessTokenIssuer tokenIssuer;
    private final RefreshTokenStore refreshTokens;
    private final SessionRegistry sessions;
    private final LoginRateLimiter rateLimiter;

    public AuthGrpcService(UserServiceGrpc.UserServiceBlockingStub userService,
                           AccessTokenIssuer tokenIssuer,
                           RefreshTokenStore refreshTokens,
                           SessionRegistry sessions,
                           LoginRateLimiter rateLimiter) {
        this.userService = userService;
        this.tokenIssuer = tokenIssuer;
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void login(LoginRequest request, StreamObserver<LoginResponse> observer) {
        LoginRateLimiter.Decision decision = rateLimiter.check(request.getUsername(), request.getClientIp());
        if (!decision.allowed()) {
            respond(observer, LoginResponse.newBuilder()
                    .setOutcome(LoginResponse.Outcome.OUTCOME_RATE_LIMITED)
                    .setRetryAfter(toProto(decision.retryAfter()))
                    .build());
            return;
        }

        VerifyCredentialsResponse verdict = userService.verifyCredentials(VerifyCredentialsRequest.newBuilder()
                .setUsername(request.getUsername())
                .setPassword(request.getPassword())
                .setClientIp(request.getClientIp())
                .setUserAgent(request.getUserAgent())
                .build());

        switch (verdict.getResult()) {
            case RESULT_INVALID_CREDENTIALS -> respond(observer, LoginResponse.newBuilder()
                    .setOutcome(LoginResponse.Outcome.OUTCOME_INVALID_CREDENTIALS)
                    .build());
            case RESULT_DISABLED -> respond(observer, LoginResponse.newBuilder()
                    .setOutcome(LoginResponse.Outcome.OUTCOME_DISABLED)
                    .build());
            case RESULT_LOCKED -> respond(observer, LoginResponse.newBuilder()
                    .setOutcome(LoginResponse.Outcome.OUTCOME_LOCKED)
                    .setRetryAfter(verdict.getLockedUntil())
                    .build());
            case RESULT_OK -> respond(observer, onSuccess(request, verdict.getUser()));
            default -> respond(observer, LoginResponse.newBuilder()
                    .setOutcome(LoginResponse.Outcome.OUTCOME_INVALID_CREDENTIALS)
                    .build());
        }
    }

    private LoginResponse onSuccess(LoginRequest request, User user) {
        rateLimiter.reset(request.getUsername());
        UUID userId = UUID.fromString(user.getId());

        String sessionId = sessions.open(userId, user.getUsername(),
                request.getClientIp(), request.getUserAgent());

        // A forced password change gets a token that can do exactly one thing. Handing out the
        // full permission set here would make must_change_password a suggestion rather than a gate.
        boolean mustChange = user.getMustChangePassword();
        Set<String> permissions = mustChange
                ? Set.of(Permissions.AUTH_CHANGE_OWN_PASSWORD)
                : new LinkedHashSet<>(user.getPermissionsList());
        Set<String> scopes = mustChange ? Set.of() : entityScopesOf(user.getRolesList());

        AccessTokenIssuer.IssuedToken access =
                tokenIssuer.issue(userId, user.getUsername(), sessionId, permissions, scopes);
        RefreshTokenStore.Issued refresh = refreshTokens.issue(userId, sessionId);

        return LoginResponse.newBuilder()
                .setOutcome(mustChange
                        ? LoginResponse.Outcome.OUTCOME_PASSWORD_CHANGE_REQUIRED
                        : LoginResponse.Outcome.OUTCOME_SUCCESS)
                .setTokens(TokenPair.newBuilder()
                        .setAccessToken(access.token())
                        .setRefreshToken(refresh.token())
                        .setAccessExpiresAt(toProto(access.expiresAt()))
                        .setRefreshExpiresAt(toProto(refresh.expiresAt()))
                        .setSessionId(sessionId)
                        .build())
                .setUser(user)
                .build();
    }

    @Override
    public void refresh(RefreshRequest request, StreamObserver<RefreshResponse> observer) {
        RefreshTokenStore.RotationResult result = refreshTokens.rotate(request.getRefreshToken());

        switch (result) {
            case RefreshTokenStore.RotationResult.Invalid ignored -> respond(observer, RefreshResponse.newBuilder()
                    .setOutcome(RefreshResponse.Outcome.OUTCOME_INVALID)
                    .build());

            case RefreshTokenStore.RotationResult.ReuseDetected reuse -> {
                // Two holders of one single-use token means one of them stole it. Everything the
                // family owns is dropped, including the live sessions it backs.
                log.warn("Refresh token reuse detected for user {} (family {}); revoking all sessions",
                        reuse.userId(), reuse.familyId());
                refreshTokens.killAllFamilies(reuse.userId());
                sessions.closeAll(reuse.userId());
                respond(observer, RefreshResponse.newBuilder()
                        .setOutcome(RefreshResponse.Outcome.OUTCOME_REUSE_DETECTED)
                        .build());
            }

            case RefreshTokenStore.RotationResult.Rotated rotated -> {
                if (!sessions.isActive(rotated.sessionId())) {
                    respond(observer, RefreshResponse.newBuilder()
                            .setOutcome(RefreshResponse.Outcome.OUTCOME_INVALID)
                            .build());
                    return;
                }
                // Permissions are re-read from user-service on every refresh, so a role change
                // takes effect within one access-token lifetime instead of one refresh lifetime.
                User user = userService.getUser(uz.drenix.identity.grpc.v1.GetUserRequest.newBuilder()
                        .setId(rotated.userId().toString())
                        .build());

                AccessTokenIssuer.IssuedToken access = tokenIssuer.issue(
                        rotated.userId(), user.getUsername(), rotated.sessionId(),
                        user.getPermissionsList(), entityScopesOf(user.getRolesList()));

                respond(observer, RefreshResponse.newBuilder()
                        .setOutcome(RefreshResponse.Outcome.OUTCOME_SUCCESS)
                        .setTokens(TokenPair.newBuilder()
                                .setAccessToken(access.token())
                                .setRefreshToken(rotated.newToken())
                                .setAccessExpiresAt(toProto(access.expiresAt()))
                                .setRefreshExpiresAt(toProto(rotated.expiresAt()))
                                .setSessionId(rotated.sessionId())
                                .build())
                        .build());
            }
        }
    }

    @Override
    public void logout(LogoutRequest request, StreamObserver<AuthEmpty> observer) {
        UUID userId = UUID.fromString(request.getUserId());
        if (request.getAllSessions()) {
            sessions.closeAll(userId);
            refreshTokens.killAllFamilies(userId);
        } else {
            sessions.close(request.getSessionId());
        }
        respond(observer, AuthEmpty.getDefaultInstance());
    }

    /**
     * Sessions of any user, for an administrator.
     *
     * <p>The permission check lives at the gateway rather than here, matching the rest of this
     * service: auth-service has no token to inspect on the way in — the mTLS peer allowlist is its
     * gate — and the gateway has already verified the caller's token before forwarding.
     */
    @Override
    public void listSessions(ListSessionsRequest request, StreamObserver<ListSessionsResponse> observer) {
        UUID userId = UUID.fromString(request.getUserId());
        ListSessionsResponse.Builder response = ListSessionsResponse.newBuilder();
        for (SessionRegistry.Session session : sessions.listSessions(userId)) {
            SessionInfo.Builder info = SessionInfo.newBuilder()
                    .setSessionId(session.sessionId())
                    .setClientIp(session.clientIp())
                    .setUserAgent(session.userAgent());
            if (session.openedAt() != null) {
                info.setOpenedAt(toProto(session.openedAt()));
            }
            response.addSessions(info.build());
        }
        respond(observer, response.build());
    }

    /**
     * Ends one session, or every session the user has when no id is given.
     *
     * <p>Refresh-token families die with the sessions. Closing a session while leaving its refresh
     * token alive would let the holder mint a new session immediately, which is not revocation.
     */
    @Override
    public void revokeSessions(RevokeSessionsRequest request, StreamObserver<AuthEmpty> observer) {
        UUID userId = UUID.fromString(request.getUserId());
        if (request.getSessionId().isBlank()) {
            sessions.closeAll(userId);
            refreshTokens.killAllFamilies(userId);
        } else {
            sessions.close(request.getSessionId());
        }
        respond(observer, AuthEmpty.getDefaultInstance());
    }

    /**
     * Which companies the user may act on, derived from role assignments.
     * An unscoped (global) assignment yields GLOBAL, which the verifier treats as all four.
     */
    private static Set<String> entityScopesOf(List<RoleAssignment> assignments) {
        Set<String> scopes = new LinkedHashSet<>();
        for (RoleAssignment assignment : assignments) {
            scopes.add(assignment.getEntity() == Entity.ENTITY_UNSPECIFIED
                    ? "GLOBAL"
                    : assignment.getEntity().name().replace("ENTITY_", ""));
        }
        return scopes;
    }

    private static Timestamp toProto(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static <T> void respond(StreamObserver<T> observer, T value) {
        observer.onNext(value);
        observer.onCompleted();
    }
}
