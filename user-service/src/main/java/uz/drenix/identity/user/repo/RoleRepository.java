package uz.drenix.identity.user.repo;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.drenix.identity.user.domain.RoleEntity;

public interface RoleRepository extends JpaRepository<RoleEntity, String> {

    List<RoleEntity> findAllByOrderByCodeAsc();

    /** Flattens the permissions of several roles in one query, for token minting. */
    @Query("""
           SELECT DISTINCT p FROM RoleEntity r JOIN r.permissions p
           WHERE r.code IN :codes
           """)
    Set<String> findPermissionCodes(@Param("codes") Collection<String> codes);

    @Query("SELECT COUNT(ur) FROM UserRoleEntity ur WHERE ur.roleCode = :code")
    long countAssignments(@Param("code") String code);
}
