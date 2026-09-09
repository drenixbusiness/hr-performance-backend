package uz.drenix.identity.notification.config;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** How the two generators behave, and where they read from. */
@ConfigurationProperties(prefix = "drenix.notification")
public class NotificationProperties {

    /** How many audit entries one poll may turn into notifications. */
    private int auditBatch = 200;

    /**
     * How long a notification is kept. Old ones are deleted rather than archived: nothing reads
     * them after a week, and the audit log remains the permanent record.
     */
    private Duration retention = Duration.ofDays(30);

    /**
     * The clock window a shift covers, matching the activity report's own default. The
     * performance check judges the shift that started on the day it looks at.
     */
    private LocalTime shiftStart = LocalTime.of(18, 0);
    private LocalTime shiftEnd = LocalTime.of(3, 0);

    /**
     * The zone the shift window is expressed in. Must match performance-service's RC_ZONE, or the
     * check judges hours nobody worked.
     */
    private ZoneId zone = ZoneId.of("Asia/Tashkent");

    /**
     * How many days back the performance check will catch up over if it has been down. Bounded so
     * that a service off for a month does not fill every inbox on the morning it returns.
     */
    private int maxCatchUpDays = 3;

    public int getAuditBatch() { return auditBatch; }
    public void setAuditBatch(int auditBatch) { this.auditBatch = auditBatch; }
    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) { this.retention = retention; }
    public LocalTime getShiftStart() { return shiftStart; }
    public void setShiftStart(LocalTime shiftStart) { this.shiftStart = shiftStart; }
    public LocalTime getShiftEnd() { return shiftEnd; }
    public void setShiftEnd(LocalTime shiftEnd) { this.shiftEnd = shiftEnd; }
    public ZoneId getZone() { return zone; }
    public void setZone(ZoneId zone) { this.zone = zone; }
    public int getMaxCatchUpDays() { return maxCatchUpDays; }
    public void setMaxCatchUpDays(int maxCatchUpDays) { this.maxCatchUpDays = maxCatchUpDays; }
}
