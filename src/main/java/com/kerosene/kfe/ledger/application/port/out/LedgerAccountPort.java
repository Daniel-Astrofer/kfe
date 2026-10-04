package com.kerosene.kfe.ledger.application.port.out;

import com.kerosene.kfe.ledger.domain.LedgerBalance;
import java.util.UUID;

/** Loads and stores one ledger account under the caller's transaction and row lock. */
public interface LedgerAccountPort {
    LedgerBalance lock(UUID walletId, String asset);
    void save(LedgerBalance balance);
}
