package source.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfePaymentRequestEntity;
import source.kfe.model.KfePaymentRequestStatus;
import source.kfe.model.KfeRail;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfePaymentRequestRepository extends JpaRepository<KfePaymentRequestEntity, UUID> {

    Optional<KfePaymentRequestEntity> findByIdAndUserId(UUID id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from KfePaymentRequestEntity p where p.id = :id")
    Optional<KfePaymentRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<KfePaymentRequestEntity> findByPublicId(String publicId);

    List<KfePaymentRequestEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<KfePaymentRequestEntity> findByWalletIdAndStatusOrderByCreatedAtDesc(
            UUID walletId,
            KfePaymentRequestStatus status);

    List<KfePaymentRequestEntity> findByStatusAndRailOrderByCreatedAtAsc(
            KfePaymentRequestStatus status,
            KfeRail rail,
            Pageable pageable);

    List<KfePaymentRequestEntity> findByStatusInAndRailOrderByCreatedAtAsc(
            List<KfePaymentRequestStatus> statuses,
            KfeRail rail,
            Pageable pageable);
}
