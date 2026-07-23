package source.kfe.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfeBalanceEntity;
import source.kfe.model.KfeBalanceId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfeBalanceRepository extends JpaRepository<KfeBalanceEntity, KfeBalanceId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from KfeBalanceEntity b where b.id.walletId = :walletId and b.id.asset = :asset")
    Optional<KfeBalanceEntity> findByWalletIdAndAssetForUpdate(
            @Param("walletId") UUID walletId,
            @Param("asset") String asset);

    @Query("select b from KfeBalanceEntity b where b.id.walletId in :walletIds")
    List<KfeBalanceEntity> findByWalletIds(@Param("walletIds") Collection<UUID> walletIds);
}
