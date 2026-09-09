package uz.drenix.edge.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import uz.drenix.platform.security.AccessTokenVerifier;
import uz.drenix.platform.security.AuthPrincipal;
import uz.drenix.platform.security.UnauthenticatedException;

/**
 * Verifies the bearer token and checks the revocation denylist.
 *
 * <p>The signature check is local — no call to auth-service — so the gateway stays up even if
 * auth-service is down. The denylist check is a single Redis lookup, which is what makes
 * "revoke now" mean now rather than "within ten minutes".
 */
@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenFilter.class);

    private static final String BEARER = "Bearer ";
    private static final String DENY_SID = "deny:sid:";
    private static final String DENY_JTI = "deny:jti:";

    private final AccessTokenVerifier verifier;
    private final StringRedisTemplate redis;

    public BearerTokenFilter(AccessTokenVerifier verifier, StringRedisTemplate redis) {
        this.verifier = verifier;
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 64) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("correlationId", correlationId);
        response.setHeader("X-Correlation-Id", correlationId);

        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith(BEARER)) {
                String rawToken = header.substring(BEARER.length());
                try {
                    AuthPrincipal principal = verifier.verify(rawToken);

                    if (isRevoked(principal)) {
                        // setStatus rather than sendError: sendError dispatches to the container
                        // error page, which the security chain then has to answer for as well.
                        // A bare 401 here matches what the entry point returns for a bad token,
                        // and says nothing about why this one was refused.
                        SecurityContextHolder.clearContext();
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        return;
                    }

                    List<SimpleGrantedAuthority> authorities = principal.permissions().stream()
                            .map(SimpleGrantedAuthority::new)
                            .toList();
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, rawToken, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (UnauthenticatedException e) {
                    // Leave the context empty and let the entry point answer 401. Answering here
                    // with a reason would tell the caller which part of the token was wrong.
                    //
                    // The reason still has to go somewhere, though: a rejection that leaves no
                    // trace at all makes "every request is 401" indistinguishable from an expired
                    // token, an unreachable JWKS endpoint or a clock skew. DEBUG keeps it out of
                    // production logs by default and out of the response always.
                    log.debug("Rejected access token", e);
                    SecurityContextHolder.clearContext();
                }
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isRevoked(AuthPrincipal principal) {
        return Boolean.TRUE.equals(redis.hasKey(DENY_SID + principal.sessionId()))
               || Boolean.TRUE.equals(redis.hasKey(DENY_JTI + principal.tokenId()));
    }
}
