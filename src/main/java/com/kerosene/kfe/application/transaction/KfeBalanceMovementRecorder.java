package com.kerosene.kfe.application.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.model.KfeBalanceMovementEntity;
import com.kerosene.kfe.repository.KfeBalanceMovementRepository;

import java.util.UUID;

@Component
public class KfeBalanceMovementRecorder {

    private static final Logger log = LoggerFactory.getLogger(KfeBalanceMovementRecorder.class);

    private final KfeBalanceMovementRepository movementRepository;

    public KfeBalanceMovementRecorder(KfeBalanceMovementRepository movementRepository) {
        this.movementRepository = movementRepository;
    }

    /**
     * Persist a balance movement. For idempotent credit types, skips insert when a row
     * already exists (pre-check) and swallows unique violations under race.
     *
     * @return true if a new row was written; false if skipped as duplicate credit
     */
    public boolean record(
            UUID transactionId,
            UUID walletId,
            String movementType,
            long amountSats,
            String fromBucket,
            String toBucket) {
        if (transactionId != null
                && KfeLedgerMovementTypes.isIdempotentCreditType(movementType)
                && movementRepository.existsByTransactionIdAndMovementType(transactionId, movementType)) {
            log.debug(
                    "KFE movement already present transactionId={} type={} — skip",
                    transactionId,
                    movementType);
            return false;
        }

        KfeBalanceMovementEntity movement = new KfeBalanceMovementEntity();
        movement.setTransactionId(transactionId);
        movement.setWalletId(walletId);
        movement.setMovementType(movementType);
        movement.setAmountSats(amountSats);
        movement.setFromBucket(fromBucket);
        movement.setToBucket(toBucket);
        try {
            movementRepository.save(movement);
            return true;
        } catch (DataIntegrityViolationException exception) {
            if (transactionId != null && KfeLedgerMovementTypes.isIdempotentCreditType(movementType)) {
                log.info(
                        "KFE movement race lost transactionId={} type={} — treated as idempotent skip",
                        transactionId,
                        movementType);
                return false;
            }
            throw exception;
        }
    }
}
