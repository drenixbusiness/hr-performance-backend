package uz.drenix.identity.user.repo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.drenix.identity.user.domain.PermissionEntity;

public interface PermissionRepository extends JpaRepository<PermissionEntity, String> {
    List<PermissionEntity> findAllByOrderByCodeAsc();
}
