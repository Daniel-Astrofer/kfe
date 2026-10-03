package com.kerosene.kfe.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;
import com.kerosene.kfe.model.KfeDerivationCursorEntity;
import com.kerosene.kfe.repository.KfeDerivationCursorRepository;

@Service
public class KfeDerivationCursorService {

    private KfeMaintenanceGuard maintenanceGuard = KfeMaintenanceGuard.unavailable();

    @Autowired
    public void setMaintenanceGuard(KfeMaintenanceGuard guard) {
        this.maintenanceGuard = java.util.Objects.requireNonNull(guard);
    }

    public static final String KFE_BIP84_EXTERNAL = "KFE_BIP84_EXTERNAL";

    private final KfeDerivationCursorRepository repository;

    public KfeDerivationCursorService(KfeDerivationCursorRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int nextIndex(String cursorKey) {
        boolean transactionBound = TransactionSynchronizationManager.isActualTransactionActive();
        return maintenanceGuard.executeMutation("derivation.next-index",
                () -> nextIndexAdmitted(cursorKey), ignored -> transactionBound);
    }

    private int nextIndexAdmitted(String cursorKey) {
        KfeDerivationCursorEntity cursor = repository.findByCursorKeyForUpdate(cursorKey)
                .orElseGet(() -> newCursor(cursorKey));
        int current = cursor.getLastIssuedIndex() != null ? cursor.getLastIssuedIndex() : -1;
        int next = current + 1;
        cursor.setLastIssuedIndex(next);
        repository.save(cursor);
        return next;
    }

    private KfeDerivationCursorEntity newCursor(String cursorKey) {
        KfeDerivationCursorEntity cursor = new KfeDerivationCursorEntity();
        cursor.setCursorKey(cursorKey);
        cursor.setLastIssuedIndex(-1);
        return cursor;
    }
}
