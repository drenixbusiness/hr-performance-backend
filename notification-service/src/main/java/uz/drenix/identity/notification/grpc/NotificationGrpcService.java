package uz.drenix.identity.notification.grpc;

import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.drenix.identity.grpc.v1.Empty;
import uz.drenix.identity.grpc.v1.ListNotificationsRequest;
import uz.drenix.identity.grpc.v1.ListNotificationsResponse;
import uz.drenix.identity.grpc.v1.MarkReadRequest;
import uz.drenix.identity.grpc.v1.MarkReadResponse;
import uz.drenix.identity.grpc.v1.Notification;
import uz.drenix.identity.grpc.v1.NotificationKind;
import uz.drenix.identity.grpc.v1.NotificationServiceGrpc;
import uz.drenix.identity.grpc.v1.NotificationSeverity;
import uz.drenix.identity.grpc.v1.PageInfo;
import uz.drenix.identity.grpc.v1.UnreadCountResponse;
import uz.drenix.identity.notification.domain.NotificationEntity;
import uz.drenix.identity.notification.repo.NotificationRepository;
import uz.drenix.platform.security.AuthPrincipal;
import uz.drenix.platform.security.GrpcAuthContext;
import uz.drenix.platform.security.Permissions;

/**
 * Reading your own notifications.
 *
 * <p>Every method here works on the caller from the token and never on an id from the request.
 * There is no "read somebody else's inbox" RPC, not even for an administrator: an inbox is a
 * personal thing, and an audit log already answers the question an administrator would be asking.
 */
@Service
public class NotificationGrpcService extends NotificationServiceGrpc.NotificationServiceImplBase {

    /** A first page starts above every id that exists. */
    private static final long NEWEST_FIRST = Long.MAX_VALUE;

    private static final int MAX_LIMIT = 100;

    private final NotificationRepository notifications;

    public NotificationGrpcService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Override
    @Transactional(readOnly = true)
    public void listNotifications(ListNotificationsRequest request,
                                  StreamObserver<ListNotificationsResponse> observer) {
        UUID me = caller();

        int limit = request.getPage().getLimit() <= 0
                ? 20
                : Math.min(request.getPage().getLimit(), MAX_LIMIT);
        long cursor = cursor(request.getPage().getCursor());
        String kind = request.getKind() == NotificationKind.NOTIFICATION_KIND_UNSPECIFIED
                ? ""
                : shortName(request.getKind());

        // One more than asked for: whether a next page exists is answered by fetching it, not by
        // a second count query that could disagree with the page it describes.
        List<NotificationEntity> rows = notifications.page(
                me, cursor, request.getUnreadOnly(), kind, Limit.of(limit + 1));

        boolean hasMore = rows.size() > limit;
        List<NotificationEntity> page = hasMore ? rows.subList(0, limit) : rows;

        ListNotificationsResponse.Builder response = ListNotificationsResponse.newBuilder()
                .setUnreadCount(notifications.countByRecipientIdAndReadAtIsNull(me))
                .setPage(PageInfo.newBuilder()
                        .setHasMore(hasMore)
                        .setNextCursor(page.isEmpty() || !hasMore
                                ? ""
                                : Long.toString(page.getLast().getId()))
                        .build());
        page.forEach(row -> response.addNotifications(toProto(row)));

        observer.onNext(response.build());
        observer.onCompleted();
    }

    @Override
    @Transactional(readOnly = true)
    public void unreadCount(Empty request, StreamObserver<UnreadCountResponse> observer) {
        observer.onNext(UnreadCountResponse.newBuilder()
                .setUnread(notifications.countByRecipientIdAndReadAtIsNull(caller()))
                .build());
        observer.onCompleted();
    }

    @Override
    @Transactional
    public void markRead(MarkReadRequest request, StreamObserver<MarkReadResponse> observer) {
        UUID me = caller();
        NotificationEntity row = notifications.findById(request.getId())
                .filter(n -> n.getRecipientId().equals(me))
                // Filtered rather than checked separately: "not yours" and "does not exist" answer
                // the same way, so an id cannot be probed to learn whether it belongs to somebody.
                .orElseThrow(() -> new NoSuchElementException("No such notification"));

        if (row.getReadAt() == null) {
            row.setReadAt(Instant.now());
            notifications.save(row);
        }
        observer.onNext(MarkReadResponse.newBuilder()
                .setUnread(notifications.countByRecipientIdAndReadAtIsNull(me))
                .build());
        observer.onCompleted();
    }

    @Override
    @Transactional
    public void markAllRead(Empty request, StreamObserver<MarkReadResponse> observer) {
        UUID me = caller();
        notifications.markAllRead(me, Instant.now());
        observer.onNext(MarkReadResponse.newBuilder().setUnread(0).build());
        observer.onCompleted();
    }

    /**
     * The signed-in caller.
     *
     * <p>{@code notification:read} is held by every role — there is no reading anybody else's
     * inbox to gate — so this is really a check that a full token was presented, not a
     * password-change-scoped one.
     */
    private static UUID caller() {
        AuthPrincipal principal = GrpcAuthContext.require(Permissions.NOTIFICATION_READ);
        return principal.userId();
    }

    private static long cursor(String value) {
        if (value == null || value.isBlank()) {
            return NEWEST_FIRST;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cursor must be the id of the last row you saw");
        }
    }

    private static String shortName(NotificationKind kind) {
        return kind.name().replace("NOTIFICATION_KIND_", "");
    }

    private static Notification toProto(NotificationEntity row) {
        return Notification.newBuilder()
                .setId(row.getId())
                .setKind(NotificationKind.valueOf("NOTIFICATION_KIND_" + row.getKind()))
                .setSeverity(NotificationSeverity.valueOf(
                        "NOTIFICATION_SEVERITY_" + row.getSeverity()))
                .setTitle(row.getTitle())
                .setBody(row.getBody())
                .setSubjectUserId(row.getSubjectId() == null ? "" : row.getSubjectId().toString())
                .setSubjectName(row.getSubjectName() == null ? "" : row.getSubjectName())
                .setOccurredAt(row.getOccurredAt().toString())
                .setCreatedAt(row.getCreatedAt().toString())
                .setReadAt(row.getReadAt() == null ? "" : row.getReadAt().toString())
                .setReference(row.getReference())
                .build();
    }
}
