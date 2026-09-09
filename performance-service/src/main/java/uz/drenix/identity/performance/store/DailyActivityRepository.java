package uz.drenix.identity.performance.store;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyActivityRepository
        extends JpaRepository<DailyActivityEntity, DailyActivityEntity.Key> {

    /** These people, over this range, oldest shift first. */
    @Query("""
           select d from DailyActivityEntity d
            where d.id.userId in :users
              and d.id.shiftDate between :from and :to
            order by d.id.shiftDate asc
           """)
    List<DailyActivityEntity> range(@Param("users") Collection<UUID> users,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    /**
     * The rows already stored for a date, so a refresh can update rather than duplicate.
     *
     * <p>Loaded as a batch rather than looked up per person: the snapshot writes everybody at
     * once, and one query beats one per recruiter.
     */
    @Query("select d from DailyActivityEntity d where d.id.shiftDate = :date")
    List<DailyActivityEntity> onDate(@Param("date") LocalDate date);
}
