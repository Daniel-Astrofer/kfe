package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;

/** Readiness rule used by signup provisioning and internal recovery paths. */
public final class PrimaryWalletPolicy {
    private PrimaryWalletPolicy() {
    }

    public static boolean isReady(WalletKind kind, WalletStatus status, boolean spendable) {
        return kind == WalletKind.INTERNAL && status == WalletStatus.ACTIVE && spendable;
    }
}
