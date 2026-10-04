package com.kerosene.kfe.ledger.application.service;

import com.kerosene.kfe.ledger.domain.SolvencyPolicy;
import com.kerosene.kfe.ledger.domain.SolvencySnapshot;

/** Application boundary for the pure solvency policy. */
public final class SolvencyService {
    private final SolvencyPolicy policy;

    public SolvencyService(SolvencyPolicy policy) {
        this.policy = policy;
    }

    public SolvencySnapshot evaluate(long customerLiabilitiesSats, long systemProfitSats,
            long inFlightWithdrawalSats, long eligibleAssetsSats) {
        return policy.evaluate(customerLiabilitiesSats, systemProfitSats,
                inFlightWithdrawalSats, eligibleAssetsSats);
    }
}
