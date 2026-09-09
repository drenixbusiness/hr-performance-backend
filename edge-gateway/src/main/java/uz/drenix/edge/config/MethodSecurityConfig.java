package uz.drenix.edge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** Enables {@code @PreAuthorize} on the controllers. */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}
