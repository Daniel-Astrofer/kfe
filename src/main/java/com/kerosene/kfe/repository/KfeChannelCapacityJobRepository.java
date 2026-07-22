package source.kfe.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import source.kfe.model.KfeChannelCapacityIntent;
import source.kfe.model.KfeChannelCapacityJobEntity;
import source.kfe.model.KfeChannelCapacityJobStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KfeChannelCapacityJobRepository extends JpaRepository<KfeChannelCapacityJobEntity, UUID> {

    List<KfeChannelCapacityJobEntity> findByStatusOrderByCreatedAtAsc(
            KfeChannelCapacityJobStatus status,
            Pageable pageable);

    Optional<KfeChannelCapacityJobEntity> findFirstByIntentAndPeerPubkeyAndStatusIn(
            KfeChannelCapacityIntent intent,
            String peerPubkey,
            Collection<KfeChannelCapacityJobStatus> statuses);

    Optional<KfeChannelCapacityJobEntity> findFirstByIntentAndChannelPointAndStatusIn(
            KfeChannelCapacityIntent intent,
            String channelPoint,
            Collection<KfeChannelCapacityJobStatus> statuses);

    long countByIntentAndStatusIn(
            KfeChannelCapacityIntent intent,
            Collection<KfeChannelCapacityJobStatus> statuses);
}
