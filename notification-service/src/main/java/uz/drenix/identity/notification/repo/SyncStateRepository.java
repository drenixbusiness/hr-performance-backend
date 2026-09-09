package uz.drenix.identity.notification.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.drenix.identity.notification.domain.SyncStateEntity;

public interface SyncStateRepository extends JpaRepository<SyncStateEntity, String> {
}
