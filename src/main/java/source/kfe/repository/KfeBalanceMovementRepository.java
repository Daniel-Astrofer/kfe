package source.kfe.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfeBalanceMovementEntity;

import java.util.Collection;
import java.util.UUID;

@Repository
public interface KfeBalanceMovementRepository extends JpaRepository<KfeBalanceMovementEntity, UUID> {

    boolean existsByTransactionIdAndMovementType(UUID transactionId, String movementType);

    boolean existsByTransactionIdAndMovementTypeIn(UUID transactionId, Collection<String> movementTypes);
}
