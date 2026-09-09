package uz.drenix.identity.user.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.drenix.identity.user.domain.ActivityStandardEntity;

public interface ActivityStandardRepository extends JpaRepository<ActivityStandardEntity, Short> {
}
