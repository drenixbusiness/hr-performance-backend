package uz.drenix.identity.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Notifications.
 *
 * <p>Everything this service reports on happens somewhere else. It polls the audit log and reads
 * the activity report; it is never in the path of the action it describes, so a notification
 * failure can never fail somebody's login or user creation.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
