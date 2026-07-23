package com.kerosene.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.model.KfeWalletAddressEntity;
import com.kerosene.kfe.model.KfeWalletAddressStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfeWalletAddressRepository extends JpaRepository<KfeWalletAddressEntity, UUID> {

    List<KfeWalletAddressEntity> findByWalletIdAndStatusOrderByCreatedAtDesc(
            UUID walletId,
            KfeWalletAddressStatus status);

    List<KfeWalletAddressEntity> findByWalletIdOrderByCreatedAtDesc(UUID walletId);

    Optional<KfeWalletAddressEntity> findTopByWalletIdAndStatusOrderByCreatedAtDesc(
            UUID walletId,
            KfeWalletAddressStatus status);

    Optional<KfeWalletAddressEntity> findFirstByAddressIgnoreCase(String address);
}
