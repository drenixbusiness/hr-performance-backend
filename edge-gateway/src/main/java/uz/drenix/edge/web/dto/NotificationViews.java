package uz.drenix.edge.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import uz.drenix.identity.grpc.v1.ListNotificationsResponse;
import uz.drenix.identity.grpc.v1.Notification;

/** Notifications, protobuf to plain records. */
public final class NotificationViews {

    private NotificationViews() {
    }

    @Schema(description = "One notification.")
    public record NotificationView(
            @Schema(description = "Send this back to mark it read.", example = "418") long id,
            @Schema(description = "`ACTIVITY` — somebody did something. `PERFORMANCE` — somebody "
                                  + "missed the daily standard.",
                    example = "PERFORMANCE",
                    allowableValues = {"ACTIVITY", "PERFORMANCE"}) String kind,
            @Schema(description = "`INFO` — a record, nothing is wrong. `WARNING` — somebody is "
                                  + "short of a target. `ALERT` — an action was refused, or every "
                                  + "target was missed.",
                    example = "WARNING",
                    allowableValues = {"INFO", "WARNING", "ALERT"}) String severity,
            @Schema(description = "One line, already written for a human. Show it as it is.",
                    example = "Isaac Taylor missed the standard on 2026-08-27") String title,
            @Schema(description = "The detail behind the title.",
                    example = "82 of 125 calls, 47m of 60m talking.") String body,
            @Schema(description = "The person this is about, when there is one. Null otherwise.",
                    example = "3d5a399e-2df0-4c06-9e74-098dae3243c9") String subjectUserId,
            @Schema(description = "Their name, so the client need not look it up.",
                    example = "Isaac Taylor") String subjectName,
            @Schema(description = "When the reported thing happened — the end of the shift, or the "
                                  + "moment of the action. Not when the row was written.",
                    example = "2026-08-27T22:00:00Z") Instant occurredAt,
            @Schema(example = "2026-08-28T04:31:02Z") Instant createdAt,
            @Schema(description = "Null while unread.", example = "null") Instant readAt,
            @Schema(description = "For `ACTIVITY`, the audit action. For `PERFORMANCE`, the date "
                                  + "of the shift. Group or filter on it without parsing the text.",
                    example = "2026-08-27") String reference) {
    }

    @Schema(description = "A page of your notifications, newest first.")
    public record NotificationPageView(
            List<NotificationView> items,
            @Schema(description = "Send back as `?cursor=`. Null on the last page.",
                    example = "391") String nextCursor,
            @Schema(example = "true") boolean hasMore,
            @Schema(description = "Unread across everything, not just this page — this is the "
                                  + "number for the badge.", example = "7") int unreadCount) {
    }

    @Schema(description = "How many are left unread.")
    public record UnreadView(@Schema(example = "6") int unread) {
    }

    public static NotificationPageView of(ListNotificationsResponse response) {
        return new NotificationPageView(
                response.getNotificationsList().stream().map(NotificationViews::of).toList(),
                response.getPage().getNextCursor().isEmpty()
                        ? null
                        : response.getPage().getNextCursor(),
                response.getPage().getHasMore(),
                response.getUnreadCount());
    }

    private static NotificationView of(Notification n) {
        return new NotificationView(
                n.getId(),
                n.getKind().name().replace("NOTIFICATION_KIND_", ""),
                n.getSeverity().name().replace("NOTIFICATION_SEVERITY_", ""),
                n.getTitle(),
                n.getBody(),
                emptyToNull(n.getSubjectUserId()),
                emptyToNull(n.getSubjectName()),
                instant(n.getOccurredAt()),
                instant(n.getCreatedAt()),
                instant(n.getReadAt()),
                n.getReference());
    }

    private static Instant instant(String value) {
        return value == null || value.isEmpty() ? null : Instant.parse(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
