package com.kerosene.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.model.KfeLightningLiquidityReservationEntity;
import com.kerosene.kfe.model.KfeLiquidityReservationStatus;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfeLightningLiquidityReservationRepository
        extends JpaRepository<KfeLightningLiquidityReservationEntity, UUID> {

    Optional<KfeLightningLiquidityReservationEntity> findByTransactionId(UUID transactionId);

    @Query("""
            select coalesce(sum(r.amountSats), 0)
            from KfeLightningLiquidityReservationEntity r
            where r.status = :status
            """)
    long sumAmountByStatus(@Param("status") KfeLiquidityReservationStatus status);

    /**
     * Transaction-scoped advisory lock for the platform Lightning liquidity pool.
     * Prevents check-then-act races across concurrent outbound submissions.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(:lockKey)", nativeQuery = true)
    void acquirePoolLock(@Param("lockKey") long lockKey);
}
