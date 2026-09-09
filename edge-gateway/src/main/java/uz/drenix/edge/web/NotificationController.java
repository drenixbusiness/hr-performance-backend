package uz.drenix.edge.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.drenix.edge.web.dto.NotificationViews;
import uz.drenix.identity.grpc.v1.Empty;
import uz.drenix.identity.grpc.v1.ListNotificationsRequest;
import uz.drenix.identity.grpc.v1.MarkReadRequest;
import uz.drenix.identity.grpc.v1.NotificationKind;
import uz.drenix.identity.grpc.v1.NotificationServiceGrpc;
import uz.drenix.identity.grpc.v1.PageRequest;

/**
 * Your notifications.
 *
 * <p>Every endpoint here works on the signed-in caller. There is no id in any path that names
 * whose inbox to read: an inbox is personal, and an administrator asking what happened has the
 * audit log for that.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationServiceGrpc.NotificationServiceBlockingStub notifications;

    public NotificationController(
            NotificationServiceGrpc.NotificationServiceBlockingStub notifications) {
        this.notifications = notifications;
    }

    @Operation(
            summary = "Your notifications, newest first",
            description = """
                    Everything you have been told about, most recent first. Held by every role — \
                    you only ever see your own.

                    ## The two kinds

                    | `kind` | What it is | Who gets it |
                    |---|---|---|
                    | `ACTIVITY` | somebody performed an action — created an account, changed a \
                    role, signed in | the leads of the person's own company, and everyone who sees \
                    the whole organisation (CEO, SUPER_ADMIN) |
                    | `PERFORMANCE` | somebody missed the daily standard on a finished shift | the \
                    leads of that person's company — including the lead themselves when it is \
                    their own shift |

                    Nobody is notified of their own actions. `PERFORMANCE` is the exception: a \
                    lead is in their own company, so a lead who misses the standard hears about \
                    it, because otherwise they are the one person nobody ever tells.

                    ## Severity

                    | `severity` | Meaning |
                    |---|---|
                    | `INFO` | a record. Something happened and it succeeded |
                    | `WARNING` | one or two of the three daily targets were missed |
                    | `ALERT` | an action was refused or failed, or all three targets were missed |

                    ## Paging

                    Cursor-based, like the rest of this API. Send `nextCursor` back as `?cursor=` \
                    and stop when `hasMore` is false. `unreadCount` is across everything, not just \
                    the page — it is the number for the badge.

                    ## Timing

                    Actions appear within about a minute. A missed standard appears once the shift \
                    has **finished**: judging a shift while it is still running would report \
                    everybody as failing until the last hour of it.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of your notifications."),
            @ApiResponse(responseCode = "400", description = "The cursor is not a notification id.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or expired token.")})
    @GetMapping
    @PreAuthorize("hasAuthority('notification:read')")
    public NotificationViews.NotificationPageView list(
            @Parameter(description = "Page size, 1–100.", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,

            @Parameter(description = "The `nextCursor` from the previous page. Omit for the first.",
                       example = "391")
            @RequestParam(required = false) String cursor,

            @Parameter(description = "Only what you have not read yet.", example = "false")
            @RequestParam(defaultValue = "false") boolean unreadOnly,

            @Parameter(description = "`ACTIVITY` or `PERFORMANCE`. Omit for both.",
                       example = "PERFORMANCE")
            @RequestParam(required = false) String kind) {

        ListNotificationsRequest.Builder request = ListNotificationsRequest.newBuilder()
                .setUnreadOnly(unreadOnly)
                .setPage(PageRequest.newBuilder()
                        .setLimit(limit)
                        .setCursor(cursor == null ? "" : cursor)
                        .build());
        if (kind != null && !kind.isBlank()) {
            request.setKind(kindOf(kind));
        }
        return NotificationViews.of(notifications.listNotifications(request.build()));
    }

    @Operation(
            summary = "How many you have not read",
            description = """
                    Just the number, for a badge. Cheaper than fetching a page you are not going \
                    to show, so poll this rather than the list.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The unread count."),
            @ApiResponse(responseCode = "401", description = "Missing or expired token.")})
    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('notification:read')")
    public NotificationViews.UnreadView unreadCount() {
        return new NotificationViews.UnreadView(
                notifications.unreadCount(Empty.getDefaultInstance()).getUnread());
    }

    @Operation(
            summary = "Mark one as read",
            description = """
                    Marks one of **your** notifications read and answers with what is left unread, \
                    so a badge can be updated without a second call.

                    An id that is not yours answers 404, exactly as one that does not exist would: \
                    an id cannot be used to find out whether somebody else was notified of \
                    something.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Marked. The unread count follows."),
            @ApiResponse(responseCode = "401", description = "Missing or expired token."),
            @ApiResponse(responseCode = "404", description = "No such notification of yours.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/{id}/read")
    @PreAuthorize("hasAuthority('notification:read')")
    public NotificationViews.UnreadView markRead(
            @Parameter(description = "The notification's id.", example = "418", required = true)
            @PathVariable long id) {

        return new NotificationViews.UnreadView(
                notifications.markRead(MarkReadRequest.newBuilder().setId(id).build()).getUnread());
    }

    @Operation(
            summary = "Mark everything read",
            description = "Clears your badge in one call. Nothing is deleted; the list is unchanged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All marked. `unread` is now 0."),
            @ApiResponse(responseCode = "401", description = "Missing or expired token.")})
    @PostMapping("/read-all")
    @PreAuthorize("hasAuthority('notification:read')")
    public NotificationViews.UnreadView markAllRead() {
        return new NotificationViews.UnreadView(
                notifications.markAllRead(Empty.getDefaultInstance()).getUnread());
    }

    /**
     * The enum name the proto uses, from the short one the API speaks.
     *
     * <p>Rejected here rather than downstream, because an unknown name is a caller mistake and
     * {@code valueOf} would surface it as a 500.
     */
    private static NotificationKind kindOf(String kind) {
        try {
            return NotificationKind.valueOf(
                    "NOTIFICATION_KIND_" + kind.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("kind must be ACTIVITY or PERFORMANCE");
        }
    }
}
