package com.kerosene.kfe.ledger.application.port.out;

import com.kerosene.kfe.ledger.domain.LedgerBalance;
import com.kerosene.kfe.ledger.domain.LedgerBalance.Transition;

/** Optional projection hook; financial state is already persisted before it is called. */
public interface LedgerMutationObserver {
    void afterApplied(LedgerBalance balance, Transition transition);
}
