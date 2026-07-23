package com.kerosene.kfe.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.model.KfeChannelRebalanceJobEntity;
import com.kerosene.kfe.model.KfeChannelRebalanceJobStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfeChannelRebalanceJobRepository extends JpaRepository<KfeChannelRebalanceJobEntity, UUID> {

    Optional<KfeChannelRebalanceJobEntity> findFirstByChannelPointAndStatusIn(
            String channelPoint,
            Collection<KfeChannelRebalanceJobStatus> statuses);

    List<KfeChannelRebalanceJobEntity> findByStatusOrderByCreatedAtAsc(
            KfeChannelRebalanceJobStatus status,
            Pageable pageable);
}
