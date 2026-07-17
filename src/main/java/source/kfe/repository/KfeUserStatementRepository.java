package source.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfeUserStatementEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfeUserStatementRepository extends JpaRepository<KfeUserStatementEntity, UUID> {

    List<KfeUserStatementEntity> findTop25ByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(
            Long userId,
            LocalDateTime now);

    boolean existsByUserIdAndTransactionId(Long userId, UUID transactionId);

    Optional<KfeUserStatementEntity> findByUserIdAndTransactionId(Long userId, UUID transactionId);

    /**
     * @deprecated Prefer {@link #existsByUserIdAndTransactionId} — same tx can appear for two users
     * (internal transfer). Kept for temporary call-site compatibility during refresh paths.
     */
    @Deprecated
    boolean existsByTransactionId(UUID transactionId);

    /**
     * @deprecated Prefer {@link #findByUserIdAndTransactionId}.
     */
    @Deprecated
    Optional<KfeUserStatementEntity> findByTransactionId(UUID transactionId);

    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}
