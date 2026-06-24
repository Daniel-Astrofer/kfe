package source.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfeTaxEventClassificationEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface KfeTaxEventClassificationRepository
        extends JpaRepository<KfeTaxEventClassificationEntity, KfeTaxEventClassificationEntity.Key> {

    List<KfeTaxEventClassificationEntity> findByUserId(Long userId);

    Optional<KfeTaxEventClassificationEntity> findByUserIdAndEventId(Long userId, String eventId);
}
