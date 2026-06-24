package source.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletAddressStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfeWalletAddressRepository extends JpaRepository<KfeWalletAddressEntity, UUID> {

    List<KfeWalletAddressEntity> findByWalletIdAndStatusOrderByCreatedAtDesc(
            UUID walletId,
            KfeWalletAddressStatus status);

    Optional<KfeWalletAddressEntity> findTopByWalletIdAndStatusOrderByCreatedAtDesc(
            UUID walletId,
            KfeWalletAddressStatus status);

    Optional<KfeWalletAddressEntity> findFirstByAddressIgnoreCase(String address);
}
