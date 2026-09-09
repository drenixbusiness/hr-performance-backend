package uz.drenix.identity.performance;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class PerformanceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PerformanceServiceApplication.class, args);
    }

    /** Injected rather than read statically, so the quarter arithmetic can be tested at any date. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
