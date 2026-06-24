package source.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfePaymentRequestEntity;
import source.kfe.model.KfePaymentRequestStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfePaymentRequestRepository extends JpaRepository<KfePaymentRequestEntity, UUID> {

    Optional<KfePaymentRequestEntity> findByIdAndUserId(UUID id, Long userId);

    Optional<KfePaymentRequestEntity> findByPublicId(String publicId);

    List<KfePaymentRequestEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<KfePaymentRequestEntity> findByWalletIdAndStatusOrderByCreatedAtDesc(
            UUID walletId,
            KfePaymentRequestStatus status);
}
