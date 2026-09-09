package uz.drenix.edge.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document behind Swagger UI.
 *
 * <p>Tags are declared here rather than left to be inferred, so the order and the wording of the
 * groups are a deliberate choice instead of whatever the classpath scan happens to produce.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER_SCHEME = "accessToken";

    @Bean
    public OpenAPI drenixOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Drenix Identity API")
                        .version("v1")
                        .description("""
                                The only public surface of the Drenix backend. Everything here is \
                                REST over HTTP; internally the gateway talks to auth-service and \
                                user-service over gRPC with mutual TLS.

                                ## How to use this page

                                1. Open **Authentication → POST /api/v1/auth/login** and sign in. \
                                   The first administrator is `admin`; its password is the \
                                   `BOOTSTRAP_ADMIN_PASSWORD` from your `.env`.
                                2. Copy `accessToken` from the response.
                                3. Press the green **Authorize** button at the top right and paste \
                                   the token. Do not type the word `Bearer` — Swagger adds it.
                                4. Every other endpoint now sends the token for you.

                                ## Things that will surprise you if nobody says them

                                * **There is no registration endpoint and there never will be.** \
                                  Accounts are created by an administrator through \
                                  `POST /api/v1/admin/users`.
                                * A brand-new account, and the first administrator, come back with \
                                  `passwordChangeRequired: true`. The token issued in that state \
                                  carries exactly one permission — `auth:changeOwnPassword` — so \
                                  every other call answers **403** until the password is changed.
                                * **Access tokens live 10 minutes.** After that you get 401; call \
                                  `POST /api/v1/auth/refresh` for a new pair.
                                * **Refresh tokens are single use.** Each refresh returns a new \
                                  one. Sending the same refresh token twice is treated as theft: \
                                  every session for that user is revoked immediately.
                                * Changing a password signs the account out everywhere, including \
                                  the session that changed it.
                                """)
                        .contact(new Contact().name("Drenix").email("drenixit@gmail.com")))
                // Applied to every operation. Login and refresh opt out with
                // @Operation(security = {}), because they are what produces a token.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        Paste only the `accessToken` value from the login response. \
                                        Swagger prefixes it with `Bearer ` on its own.""")))
                .tags(List.of(
                        new Tag().name("Authentication").description("""
                                Signing in, renewing a session, signing out, and changing your own \
                                password. Login and refresh are the only endpoints in the whole API \
                                that need no token."""),
                        new Tag().name("Admin · Users").description("""
                                Creating and managing accounts. Every endpoint needs a `user:*` \
                                permission. The gateway checks it once for a fast rejection, and \
                                user-service checks it again on its own side — the second check is \
                                the one that actually protects the data."""),
                        new Tag().name("Activity").description("""
                                Calls, talk time and messages from RingCentral, against the daily \n                                standard. What you see depends on which of the three performance \n                                permissions your token carries."""),
                        new Tag().name("Performance").description("""
                                How each recruiter is doing against the hiring standard, month by \n                                month, read live from the monday.com board. Quarters are counted \n                                from each recruiter's own start rather than from January."""),
                        new Tag().name("Admin · Audit").description("""
                                What the system recorded about itself: who did what, to which \
                                account, from where, and whether it worked. Append-only — the \
                                database refuses UPDATE and DELETE, so there is no endpoint here \
                                that changes anything. Needs `audit:read`."""),
                        new Tag().name("Admin · Roles").description("""
                                Roles and the permission vocabulary. A role is a named set of \
                                permissions; a user's effective permissions are the union of the \
                                roles assigned to them. System roles (`OWNER`, \
                                `SUPER_ADMIN`, `USER_ADMIN`, `AUDITOR`) cannot be deleted.""")));
    }
}
