package com.kerosene.kfe.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.model.KfeChannelOperationDecisionEntity;
import com.kerosene.kfe.model.KfeChannelOperationType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfeChannelOperationDecisionRepository
        extends JpaRepository<KfeChannelOperationDecisionEntity, UUID> {

    List<KfeChannelOperationDecisionEntity> findByOperationOrderByCreatedAtDesc(
            KfeChannelOperationType operation,
            Pageable pageable);

    List<KfeChannelOperationDecisionEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query(
            """
            select e from KfeChannelOperationDecisionEntity e
            where e.operation = :op
              and e.passed = true
              and e.executed = false
              and lower(e.peerPubkey) = lower(:peer)
              and e.amountSats = :amount
              and e.meshInjectPhase in :phases
            order by e.createdAt desc
            """)
    List<KfeChannelOperationDecisionEntity> findResumableOpens(
            @Param("op") KfeChannelOperationType op,
            @Param("peer") String peer,
            @Param("amount") Long amount,
            @Param("phases") List<String> phases,
            Pageable pageable);

    default Optional<KfeChannelOperationDecisionEntity> findLatestResumableOpen(
            String peer, Long amount, List<String> phases) {
        List<KfeChannelOperationDecisionEntity> rows =
                findResumableOpens(
                        KfeChannelOperationType.OPEN, peer, amount, phases, Pageable.ofSize(1));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    List<KfeChannelOperationDecisionEntity> findByMeshInjectPhaseAndExecutedFalseOrderByCreatedAtAsc(
            String meshInjectPhase, Pageable pageable);

    @Query(
            """
            select e from KfeChannelOperationDecisionEntity e
            where e.operation = :op
              and e.executed = false
              and e.meshInjectPhase = 'RESERVED'
              and e.createdAt < :cutoff
            order by e.createdAt asc
            """)
    List<KfeChannelOperationDecisionEntity> findOrphanedReserves(
            @Param("op") KfeChannelOperationType op,
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable);

    default List<KfeChannelOperationDecisionEntity> findOrphanedReserves(
            LocalDateTime cutoff, Pageable pageable) {
        return findOrphanedReserves(KfeChannelOperationType.OPEN, cutoff, pageable);
    }
}
