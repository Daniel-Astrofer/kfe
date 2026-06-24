package source.kfe.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfeExecutionOutboxEntity;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfeExecutionOutboxRepository extends JpaRepository<KfeExecutionOutboxEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from KfeExecutionOutboxEntity o where o.id = :id")
    Optional<KfeExecutionOutboxEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select o from KfeExecutionOutboxEntity o
            where o.status = 'UNKNOWN'
              and o.operation in :operations
            order by o.updatedAt asc
            """)
    List<KfeExecutionOutboxEntity> findInboundReconciliationCandidates(
            @Param("operations") Collection<String> operations,
            Pageable pageable);

    @Query("""
            select o from KfeExecutionOutboxEntity o
            where (
                o.status in :dueStatuses
                and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
            ) or (
                o.status = 'PROCESSING'
                and o.claimedAt is not null
                and o.claimedAt < :staleClaimBefore
            )
            order by o.createdAt asc
            """)
    List<KfeExecutionOutboxEntity> findTop100ClaimCandidates(
            @Param("dueStatuses") Collection<String> dueStatuses,
            @Param("now") LocalDateTime now,
            @Param("staleClaimBefore") LocalDateTime staleClaimBefore);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KfeExecutionOutboxEntity o
            set o.status = 'PROCESSING',
                o.claimedBy = :workerId,
                o.claimedAt = :now
            where o.id = :id
              and (
                (
                    o.status in :dueStatuses
                    and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
                ) or (
                    o.status = 'PROCESSING'
                    and o.claimedAt is not null
                    and o.claimedAt < :staleClaimBefore
                )
              )
            """)
    int claimDue(
            @Param("id") UUID id,
            @Param("dueStatuses") Collection<String> dueStatuses,
            @Param("now") LocalDateTime now,
            @Param("staleClaimBefore") LocalDateTime staleClaimBefore,
            @Param("workerId") String workerId);
}
