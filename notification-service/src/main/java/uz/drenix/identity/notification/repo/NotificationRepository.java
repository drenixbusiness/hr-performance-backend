package uz.drenix.identity.notification.repo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.drenix.identity.notification.domain.NotificationEntity;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /**
     * One page of somebody's notifications, newest first.
     *
     * <p>Keyset paging on the id, like the rest of this system: ids are assigned in insertion
     * order, so a cursor cannot drift while new notifications arrive above it. The caller passes
     * {@link Long#MAX_VALUE} for the first page rather than a null, because Hibernate cannot
     * always infer the type of a null bind parameter.
     *
     * <p>{@code unreadOnly} is a boolean rather than two queries: the optimiser picks the partial
     * index either way, and one method is one thing to keep correct.
     */
    @Query("""
           select n from NotificationEntity n
            where n.recipientId = :recipient
              and n.id < :cursor
              and (:unreadOnly = false or n.readAt is null)
              and (:kind = '' or n.kind = :kind)
            order by n.id desc
           """)
    List<NotificationEntity> page(@Param("recipient") UUID recipient,
                                  @Param("cursor") long cursor,
                                  @Param("unreadOnly") boolean unreadOnly,
                                  @Param("kind") String kind,
                                  Limit limit);

    int countByRecipientIdAndReadAtIsNull(UUID recipientId);

    @Modifying
    @Query("update NotificationEntity n set n.readAt = :at "
           + "where n.recipientId = :recipient and n.readAt is null")
    int markAllRead(@Param("recipient") UUID recipient, @Param("at") Instant at);

    /** Which of these keys this recipient already has, so a re-run writes nothing twice. */
    @Query("select n.dedupeKey from NotificationEntity n "
           + "where n.recipientId = :recipient and n.dedupeKey in :keys")
    List<String> existingKeys(@Param("recipient") UUID recipient,
                              @Param("keys") List<String> keys);
}
