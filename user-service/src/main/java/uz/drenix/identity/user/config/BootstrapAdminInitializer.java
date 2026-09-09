package uz.drenix.identity.user.config;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.drenix.identity.user.domain.UserEntity;
import uz.drenix.identity.user.domain.UserRoleEntity;
import uz.drenix.identity.user.repo.UserRepository;
import uz.drenix.identity.user.service.PasswordHasher;

/**
 * Creates the very first administrator, because there is no registration endpoint and an empty
 * users table would otherwise be a locked door with the key inside.
 *
 * <p>The password comes from {@code DRENIX_BOOTSTRAP_ADMIN_PASSWORD} and is hashed immediately.
 * It is never written to a migration, a log line or a config file — a hash committed to git is a
 * password every contractor, past and future, already has.
 *
 * <p>Runs only when the table is empty, so a restart cannot resurrect or reset the account.
 */
@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);
    private static final String PASSWORD_VAR = "DRENIX_BOOTSTRAP_ADMIN_PASSWORD";
    private static final String USERNAME_VAR = "DRENIX_BOOTSTRAP_ADMIN_USERNAME";

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final Environment environment;

    public BootstrapAdminInitializer(UserRepository users, PasswordHasher hasher, Environment environment) {
        this.users = users;
        this.hasher = hasher;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.countByDeletedAtIsNull() > 0) {
            return;
        }

        String password = environment.getProperty(PASSWORD_VAR);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "No users exist and " + PASSWORD_VAR + " is not set. "
                    + "Set it to a strong value for the first start, then unset it.");
        }
        if (password.length() < 12) {
            throw new IllegalStateException(PASSWORD_VAR + " must be at least 12 characters");
        }

        String username = environment.getProperty(USERNAME_VAR, "admin");
        UserEntity admin = new UserEntity(
                UUID.randomUUID(), username, hasher.hash(password), "Bootstrap administrator", null);
        admin.setMustChangePassword(true);
        admin.getRoles().add(new UserRoleEntity(admin, "SUPER_ADMIN", null, null));
        users.save(admin);

        log.warn("Bootstrap administrator '{}' created. It must change its password on first login. "
                 + "Unset {} now.", username, PASSWORD_VAR);
    }
}
