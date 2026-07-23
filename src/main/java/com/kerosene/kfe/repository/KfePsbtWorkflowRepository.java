package com.kerosene.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.model.KfePsbtWorkflowEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfePsbtWorkflowRepository extends JpaRepository<KfePsbtWorkflowEntity, UUID> {

    Optional<KfePsbtWorkflowEntity> findByIdAndUserId(UUID id, Long userId);

    List<KfePsbtWorkflowEntity> findByWalletIdAndUserIdOrderByCreatedAtDesc(UUID walletId, Long userId);

    List<KfePsbtWorkflowEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}
