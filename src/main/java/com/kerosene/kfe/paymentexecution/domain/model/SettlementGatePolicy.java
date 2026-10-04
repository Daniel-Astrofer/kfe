package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Locale;

/** Immutable settlement policy. Invalid or permissive configuration is rejected at startup. */
public record SettlementGatePolicy(String lightningRiskGateMode, boolean porGateEnabled,
        boolean allowSimulatedBalances, int constitutionMemberCount, int constitutionThreshold) {
    public SettlementGatePolicy {
        lightningRiskGateMode = lightningRiskGateMode == null
                ? ""
                : lightningRiskGateMode.trim().toLowerCase(Locale.ROOT);
        if (!"enforce".equals(lightningRiskGateMode)) {
            throw new IllegalArgumentException(
                    "kfe.settlement.lightning.risk-gate-mode must be exactly 'enforce'");
        }
        if (allowSimulatedBalances) {
            throw new IllegalArgumentException(
                    "Simulated balances are forbidden; remove the obsolete simulation setting");
        }
        if (constitutionMemberCount < 1 || constitutionThreshold < 1
                || constitutionThreshold > constitutionMemberCount) {
            throw new IllegalArgumentException(
                    "Vault constitution requires member-count >= threshold >= 1; threshold > constitutionMemberCount is invalid");
        }
    }
    public boolean enforceLightningRisk() { return "enforce".equals(lightningRiskGateMode); }
}
