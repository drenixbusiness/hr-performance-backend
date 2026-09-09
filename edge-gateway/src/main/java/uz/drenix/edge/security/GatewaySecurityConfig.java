package uz.drenix.edge.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The public edge. Everything here assumes the caller is hostile until a token proves otherwise.
 */
@Configuration
public class GatewaySecurityConfig {

    /**
     * Swagger UI and the OpenAPI document, on their own chain.
     *
     * <p>They need their own because the API chain answers with {@code default-src 'none'}, which
     * forbids a page from loading any script, stylesheet or font — Swagger UI included. Rather
     * than loosen that policy for the whole gateway, the documentation gets a policy of its own
     * that permits only what it actually loads, all of it served from this origin.
     *
     * <p>Ordered ahead of the API chain so these paths never reach it.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiDocsFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .headers(headers -> headers
                    .frameOptions(frame -> frame.deny())
                    .contentTypeOptions(Customizer.withDefaults())
                    .contentSecurityPolicy(csp -> csp.policyDirectives(
                            "default-src 'self'; "
                            + "script-src 'self' 'unsafe-inline'; "
                            + "style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data:; "
                            + "font-src 'self' data:; "
                            + "connect-src 'self'; "
                            + "frame-ancestors 'none'; base-uri 'none'")));

        return http.build();
    }

    /**
     * The API itself: everything the documentation chain above did not already answer.
     *
     * <p>The qualifier is not decoration. Spring MVC publishes its own CorsConfigurationSource
     * (mvcHandlerMappingIntrospector), so this parameter is ambiguous by type and Spring falls
     * back to matching the parameter name against the bean name — which never matched here and
     * failed startup outright. Naming the bean explicitly settles it.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http, BearerTokenFilter bearerTokenFilter,
                                           @Qualifier("corsConfigurationSource")
                                           CorsConfigurationSource corsSource) throws Exception {
        http
            // No cookies anywhere: the browser holds the access token in memory and the refresh
            // token in an httpOnly cookie set by this service only for the refresh path.
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // CSRF protection is for cookie-borne credentials. With a bearer token in a header
            // there is nothing for a cross-site form to replay.
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsSource))
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .anonymous(Customizer.withDefaults())
            .exceptionHandling(handling -> handling
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth
                    // /api/health is the liveness check on this port. The actuator listens on
                    // the management port instead and is not published, so permitting
                    // /actuator/health here was permitting a path that answered 404.
                    .requestMatchers("/api/v1/auth/login",
                                     "/api/v1/auth/refresh",
                                     "/api/health").permitAll()
                    .anyRequest().authenticated())
            .headers(headers -> headers
                    .frameOptions(frame -> frame.deny())
                    .contentTypeOptions(Customizer.withDefaults())
                    .httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(63072000))
                    .referrerPolicy(referrer -> referrer.policy(
                            org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                    .ReferrerPolicy.NO_REFERRER))
                    .contentSecurityPolicy(csp -> csp.policyDirectives(
                            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")))
            .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BearerTokenFilter is a @Component, so Boot would also register it as a plain servlet filter
     * and run it a second time — on every request, including the ones the security chain excludes.
     * The chain placement below is the only registration that should exist.
     */
    @Bean
    public FilterRegistrationBean<BearerTokenFilter> bearerTokenFilterRegistration(
            BearerTokenFilter filter) {
        FilterRegistrationBean<BearerTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        // An explicit allowlist. Wildcards plus credentials is the classic way to hand any site
        // on the internet a session.
        config.setAllowedOrigins(properties.getAllowedOrigins());
        // Every method the controllers actually map. PUT was missing, which let the browser's
        // preflight fail on the two endpoints that use it — replacing a user's roles and updating
        // a role — with a CORS error rather than anything the client could diagnose.
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        // Without this the browser can see the header but JavaScript cannot read it. The
        // correlation id is useless for client-side error reports without it, and
        // Content-Disposition is how the monthly report PDF says what the file is called — a
        // download that saves itself as the endpoint path is not the report anybody asked for.
        config.setExposedHeaders(List.of("X-Correlation-Id", "Content-Disposition", "Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
