package com.kerosene.kfe.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.model.KfeFinancialNotificationOutboxEntity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfeFinancialNotificationOutboxRepository
        extends CrudRepository<KfeFinancialNotificationOutboxEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from KfeFinancialNotificationOutboxEntity o where o.id = :id")
    Optional<KfeFinancialNotificationOutboxEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select o from KfeFinancialNotificationOutboxEntity o
            where (
                o.status in :dueStatuses
                and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
            )
            order by o.createdAt asc
            """)
    List<KfeFinancialNotificationOutboxEntity> findTop100ClaimCandidates(
            @Param("dueStatuses") Collection<String> dueStatuses,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeFinancialNotificationOutboxEntity o
            set o.status = 'CLAIMED',
                o.claimedBy = :workerId,
                o.claimedUntil = :claimedUntil
            where o.id = :id
              and o.status in :dueStatuses
              and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
            """)
    int claimDue(
            @Param("id") UUID id,
            @Param("dueStatuses") Collection<String> dueStatuses,
            @Param("now") Instant now,
            @Param("workerId") String workerId,
            @Param("claimedUntil") Instant claimedUntil);

    @Modifying
    @Query("""
            update KfeFinancialNotificationOutboxEntity o
            set o.status = :status,
                o.attempts = o.attempts + 1,
                o.nextAttemptAt = :nextAttemptAt,
                o.lastError = :lastError,
                o.claimedBy = null,
                o.claimedUntil = null
            where o.id = :id
            """)
    int markRetryableFailure(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError);

    @Modifying
    @Query("""
            update KfeFinancialNotificationOutboxEntity o
            set o.status = :status,
                o.lastError = :lastError,
                o.claimedBy = null,
                o.claimedUntil = null
            where o.id = :id
            """)
    int markFinalFailure(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("lastError") String lastError);

    @Modifying
    @Query("""
            update KfeFinancialNotificationOutboxEntity o
            set o.status = 'DELIVERED',
                o.deliveredAt = :now,
                o.claimedBy = null,
                o.claimedUntil = null
            where o.id = :id
            """)
    int markDelivered(@Param("id") UUID id, @Param("now") Instant now);
}
