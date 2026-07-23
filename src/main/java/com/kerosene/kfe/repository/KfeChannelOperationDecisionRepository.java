package com.kerosene.kfe.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.model.KfeChannelOperationDecisionEntity;
import com.kerosene.kfe.model.KfeChannelOperationType;

import java.util.List;
import java.util.UUID;

@Repository
public interface KfeChannelOperationDecisionRepository
        extends JpaRepository<KfeChannelOperationDecisionEntity, UUID> {

    List<KfeChannelOperationDecisionEntity> findByOperationOrderByCreatedAtDesc(
            KfeChannelOperationType operation,
            Pageable pageable);

    List<KfeChannelOperationDecisionEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
