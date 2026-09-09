package uz.drenix.identity.notification.grpc;

import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.drenix.identity.grpc.v1.Empty;
import uz.drenix.identity.grpc.v1.ReportEventServiceGrpc;
import uz.drenix.identity.grpc.v1.ReportGeneratedRequest;
import uz.drenix.identity.notification.service.Notifications;
import uz.drenix.platform.security.AuthPrincipal;
import uz.drenix.platform.security.GrpcAuthContext;
import uz.drenix.platform.security.Permissions;

/**
 * The one notification that is pushed rather than derived.
 *
 * <p>Everything else this service reports on leaves a trace it can go and read: an audit entry, a
 * finished shift. Generating a report leaves none — it is a thing somebody did with a button, and
 * there is nothing to poll for afterwards. So this arrives as a call.
 *
 * <p>Deliberately narrow, and the narrowness is the point. It takes no recipient: the notification
 * goes to whoever's token made the call and to nobody else. There is no way to use it to write into
 * somebody else's inbox, which is what a general "notify" endpoint would have become.
 */
@Service
public class ReportEventGrpcService extends ReportEventServiceGrpc.ReportEventServiceImplBase {

    private final Notifications notifications;

    public ReportEventGrpcService(Notifications notifications) {
        this.notifications = notifications;
    }

    @Override
    public void reportGenerated(ReportGeneratedRequest request, StreamObserver<Empty> observer) {
        AuthPrincipal caller = GrpcAuthContext.require(Permissions.NOTIFICATION_READ);
        UUID me = caller.userId();

        String month = request.getMonth().isBlank() ? "the selected period" : request.getMonth();
        String body = request.getSubjects() == 1
                ? "1 recruiter covered."
                : request.getSubjects() + " recruiters covered.";
        if (!request.getFilename().isBlank()) {
            body += " " + request.getFilename();
        }

        notifications.deliver(
                new Notifications.Draft(
                        Notifications.Kind.ACTIVITY,
                        Notifications.Severity.INFO,
                        "Report generated successfully",
                        "Monthly recruiting report for " + month + ". " + body,
                        me,
                        caller.username(),
                        Instant.now(),
                        "report." + month,
                        // Keyed on the person, the month and the minute. Two clicks a minute apart
                        // are two notifications, because the second one really did produce a second
                        // file; two clicks in the same minute are one, because a double-click is
                        // not news.
                        "report:" + month + ":" + Instant.now().getEpochSecond() / 60),
                List.of(me));

        observer.onNext(Empty.getDefaultInstance());
        observer.onCompleted();
    }
}
