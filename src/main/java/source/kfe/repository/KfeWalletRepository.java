package source.kfe.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfeWalletRepository extends JpaRepository<KfeWalletEntity, UUID> {

    List<KfeWalletEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<KfeWalletEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(
            Long userId,
            Collection<KfeWalletStatus> statuses);

    boolean existsByUserIdAndKindAndStatusIn(
            Long userId,
            KfeWalletKind kind,
            Collection<KfeWalletStatus> statuses);

    long countByUserIdAndKindAndStatusIn(
            Long userId,
            KfeWalletKind kind,
            Collection<KfeWalletStatus> statuses);

    Optional<KfeWalletEntity> findFirstByUserIdAndKindOrderByCreatedAtDesc(Long userId, KfeWalletKind kind);

    Optional<KfeWalletEntity> findFirstByUserIdAndKindAndStatusInOrderByCreatedAtDesc(
            Long userId,
            KfeWalletKind kind,
            Collection<KfeWalletStatus> statuses);

    Optional<KfeWalletEntity> findByIdAndUserId(UUID id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from KfeWalletEntity w where w.id = :id and w.userId = :userId")
    Optional<KfeWalletEntity> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") Long userId);

    @Query(value = """
            SELECT
                wallet_id AS walletId,
                kind AS kind,
                status AS status,
                label AS label,
                asset AS asset,
                spendable AS spendable,
                available_sats AS availableSats,
                pending_sats AS pendingSats,
                locked_sats AS lockedSats,
                auto_hold_sats AS autoHoldSats,
                observed_sats AS observedSats,
                active_address AS activeAddress,
                created_at AS createdAt,
                updated_at AS updatedAt
            FROM financial.wallet_dashboard_view
            WHERE user_id = :userId
              AND status IN ('CREATING', 'ACTIVE', 'FROZEN', 'ROTATING_ADDRESS')
            ORDER BY created_at DESC
            """, nativeQuery = true)
    List<KfeDashboardWalletRow> findDashboardRows(@Param("userId") Long userId);
}
