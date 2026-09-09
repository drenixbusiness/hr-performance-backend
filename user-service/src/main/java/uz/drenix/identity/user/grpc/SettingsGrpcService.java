package uz.drenix.identity.user.grpc;

import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.drenix.identity.grpc.v1.ActivityStandardSettings;
import uz.drenix.identity.grpc.v1.Empty;
import uz.drenix.identity.grpc.v1.SettingsServiceGrpc;
import uz.drenix.identity.grpc.v1.UpdateActivityStandardRequest;
import uz.drenix.identity.user.domain.ActivityStandardEntity;
import uz.drenix.identity.user.repo.ActivityStandardRepository;
import uz.drenix.identity.user.service.AuditService;
import uz.drenix.platform.security.AuthPrincipal;
import uz.drenix.platform.security.GrpcAuthContext;
import uz.drenix.platform.security.Permissions;

/**
 * The editable activity standard.
 *
 * <p>Reading it is open to anyone who may read activity at all — a recruiter cannot understand
 * their own row without knowing what they were being measured against. Changing it needs
 * {@code performance:manageStandard}, which HR_LEAD, CEO and SUPER_ADMIN hold.
 *
 * <p>Every change is written to the audit log with the old and new values. A target that moved is
 * the first thing anybody asks about when the numbers suddenly look different.
 */
@Service
public class SettingsGrpcService extends SettingsServiceGrpc.SettingsServiceImplBase {

    /** Nothing in RingCentral could produce more than this; a typo, not a target. */
    private static final int MAX_PER_DAY = 100_000;

    private final ActivityStandardRepository standards;
    private final AuditService audit;

    public SettingsGrpcService(ActivityStandardRepository standards, AuditService audit) {
        this.standards = standards;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public void getActivityStandard(Empty request,
                                    StreamObserver<ActivityStandardSettings> observer) {
        // notification-service reads this too, to judge a finished shift against the same bar
        // the API reports. It arrives as a peer with no token; the RPC allowlist vouches for it.
        if (GrpcAuthContext.principalOrNull() != null) {
            GrpcAuthContext.requireAny(
                    Permissions.PERFORMANCE_READ,
                    Permissions.PERFORMANCE_READ_TEAM,
                    Permissions.PERFORMANCE_READ_ALL,
                    Permissions.PERFORMANCE_MANAGE_STANDARD);
        }

        observer.onNext(toProto(current()));
        observer.onCompleted();
    }

    @Override
    @Transactional
    public void updateActivityStandard(UpdateActivityStandardRequest request,
                                       StreamObserver<ActivityStandardSettings> observer) {
        AuthPrincipal actor = GrpcAuthContext.require(Permissions.PERFORMANCE_MANAGE_STANDARD);

        int calls = positive(request.getCallsPerDay(), MAX_PER_DAY, "callsPerDay");
        // A day is 86400 seconds and nobody talks for all of them, but the ceiling is there to
        // catch minutes typed where seconds were meant, not to judge how hard the target is.
        int talk = positive(request.getTalkSecondsPerDay(), 86_400, "talkSecondsPerDay");
        int sms = positive(request.getSmsSentPerDay(), MAX_PER_DAY, "smsSentPerDay");
        int shift = request.getShiftMinutes();
        if (shift < 1 || shift > 1440) {
            throw new IllegalArgumentException("shiftMinutes must be between 1 and 1440");
        }

        ActivityStandardEntity standard = current();
        Map<String, Object> before = Map.of(
                "callsPerDay", standard.getCallsPerDay(),
                "talkSecondsPerDay", standard.getTalkSecondsPerDay(),
                "smsSentPerDay", standard.getSmsSentPerDay(),
                "shiftMinutes", standard.getShiftMinutes());

        standard.setCallsPerDay(calls);
        standard.setTalkSecondsPerDay(talk);
        standard.setSmsSentPerDay(sms);
        standard.setShiftMinutes(shift);
        standard.setUpdatedAt(Instant.now());
        standard.setUpdatedBy(actor.username());
        ActivityStandardEntity saved = standards.save(standard);

        audit.record(actor.userId(), actor.username(), "performance.updateStandard",
                "activityStandard", "1", AuditService.Outcome.SUCCESS, null, null,
                GrpcAuthContext.correlationId(),
                Map.of("before", before,
                       "after", Map.of("callsPerDay", calls,
                                       "talkSecondsPerDay", talk,
                                       "smsSentPerDay", sms,
                                       "shiftMinutes", shift)));

        observer.onNext(toProto(saved));
        observer.onCompleted();
    }

    private ActivityStandardEntity current() {
        return standards.findById(ActivityStandardEntity.SINGLETON_ID)
                .orElseThrow(() -> new NoSuchElementException(
                        "The activity standard row is missing; V9 did not run"));
    }

    private static int positive(int value, int max, String field) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(field + " must be between 0 and " + max);
        }
        return value;
    }

    private static ActivityStandardSettings toProto(ActivityStandardEntity standard) {
        return ActivityStandardSettings.newBuilder()
                .setCallsPerDay(standard.getCallsPerDay())
                .setTalkSecondsPerDay(standard.getTalkSecondsPerDay())
                .setSmsSentPerDay(standard.getSmsSentPerDay())
                .setShiftMinutes(standard.getShiftMinutes())
                .setUpdatedAt(standard.getUpdatedAt() == null ? "" : standard.getUpdatedAt().toString())
                .setUpdatedBy(standard.getUpdatedBy() == null ? "" : standard.getUpdatedBy())
                .build();
    }
}
