package uz.drenix.identity.user.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.drenix.identity.user.domain.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByUsernameIgnoreCaseAndDeletedAtIsNull(String username);

    Optional<UserEntity> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByUsernameIgnoreCaseAndDeletedAtIsNull(String username);

    /**
     * The first of these numbers that already belongs to a live account other than {@code id}.
     *
     * <p>Two accounts must never claim one RingCentral number, or each is credited with its calls.
     * Returns the offending number rather than a boolean so the error can name it — with several
     * numbers in one request, "one of these is taken" is not an answer anybody can act on.
     *
     * <p>Pass a zero UUID when creating, so that nothing is excluded.
     */
    @Query("""
           select p from UserEntity u join u.ringCentralPhones p
            where p in :phones and u.deletedAt is null and u.id <> :id
           """)
    List<String> ringCentralPhonesTakenBySomeoneElse(@Param("phones") List<String> phones,
                                                     @Param("id") UUID id);

    boolean existsByMondayNameIgnoreCaseAndDeletedAtIsNull(String mondayName);

    boolean existsByMondayNameIgnoreCaseAndDeletedAtIsNullAndIdNot(String mondayName, UUID id);

    long countByDeletedAtIsNull();

    /**
     * Keyset pagination. (created_at, id) is a total order, so a page boundary cannot repeat or
     * skip a row while other admins create users — which OFFSET does.
     *
     * <p>No {@code :param IS NULL} branches: Hibernate cannot always infer the type of a null
     * bind parameter and refuses the query at startup. The caller passes a sentinel instead —
     * a match-everything pattern and a far-future cursor.
     */
    @Query("""
           SELECT u FROM UserEntity u
           WHERE u.deletedAt IS NULL
             AND (lower(u.username) LIKE :searchPattern OR lower(u.fullName) LIKE :searchPattern)
             AND (u.createdAt < :beforeCreatedAt
                  OR (u.createdAt = :beforeCreatedAt AND u.id < :beforeId))
           ORDER BY u.createdAt DESC, u.id DESC
           """)
    List<UserEntity> findPage(@Param("searchPattern") String searchPattern,
                              @Param("beforeCreatedAt") Instant beforeCreatedAt,
                              @Param("beforeId") UUID beforeId,
                              Limit limit);

    /**
     * Counter bump in SQL rather than read-modify-write, so two parallel failed logins cannot
     * both read 4 and both write 5, handing the attacker a free attempt.
     */
    @Modifying
    @Query("""
           UPDATE UserEntity u
           SET u.failedAttempts = u.failedAttempts + 1,
               u.updatedAt = CURRENT_TIMESTAMP
           WHERE u.id = :id
           """)
    void incrementFailedAttempts(@Param("id") UUID id);
}
