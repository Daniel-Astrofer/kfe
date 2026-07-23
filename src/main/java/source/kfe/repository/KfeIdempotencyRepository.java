package source.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfeIdempotencyEntity;

@Repository
public interface KfeIdempotencyRepository extends JpaRepository<KfeIdempotencyEntity, source.kfe.model.KfeIdempotencyId> {
}
