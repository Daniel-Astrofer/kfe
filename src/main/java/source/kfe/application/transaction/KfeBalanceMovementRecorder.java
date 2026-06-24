package source.kfe.application.transaction;

import org.springframework.stereotype.Component;
import source.kfe.model.KfeBalanceMovementEntity;
import source.kfe.repository.KfeBalanceMovementRepository;

import java.util.UUID;

@Component
public class KfeBalanceMovementRecorder {

    private final KfeBalanceMovementRepository movementRepository;

    public KfeBalanceMovementRecorder(KfeBalanceMovementRepository movementRepository) {
        this.movementRepository = movementRepository;
    }

    public void record(
            UUID transactionId,
            UUID walletId,
            String movementType,
            long amountSats,
            String fromBucket,
            String toBucket) {
        KfeBalanceMovementEntity movement = new KfeBalanceMovementEntity();
        movement.setTransactionId(transactionId);
        movement.setWalletId(walletId);
        movement.setMovementType(movementType);
        movement.setAmountSats(amountSats);
        movement.setFromBucket(fromBucket);
        movement.setToBucket(toBucket);
        movementRepository.save(movement);
    }
}
