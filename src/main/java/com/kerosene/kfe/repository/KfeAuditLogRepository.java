package source.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfeAuditLogEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfeAuditLogRepository extends JpaRepository<KfeAuditLogEntity, Long> {

    @Query(value = "SELECT pg_advisory_xact_lock( hashtext('GLOBAL_AUDIT_APPENDER') )", nativeQuery = true)
    void lockAuditAppender();

    Optional<KfeAuditLogEntity> findTopByOrderBySequenceNumberDesc();

    List<KfeAuditLogEntity> findAllByOrderBySequenceNumberAsc();

    List<KfeAuditLogEntity> findAllByOrderBySequenceNumberDesc(Pageable pageable);

    @Query("""
            select e.sequenceNumber as sequenceNumber, e.eventHash as eventHash
            from KfeAuditLogEntity e
            where e.sequenceNumber > :afterSequence
            order by e.sequenceNumber asc
            """)
    List<KfeAuditHashRow> findHashRowsAfterSequence(
            @Param("afterSequence") Long afterSequence,
            Pageable pageable);

    List<KfeAuditLogEntity> findByTransactionIdOrderBySequenceNumberAsc(UUID transactionId);
}
