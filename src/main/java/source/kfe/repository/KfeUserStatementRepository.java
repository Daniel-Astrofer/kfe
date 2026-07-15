package source.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfeUserStatementEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface KfeUserStatementRepository extends JpaRepository<KfeUserStatementEntity, UUID> {

    List<KfeUserStatementEntity> findTop25ByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(
            Long userId,
            LocalDateTime now);

    boolean existsByTransactionId(UUID transactionId);

    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}
