package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.exception.WalletRuleViolation;
import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;

/** Pure lifecycle decisions; persistence and quorum remain adapters. */
public final class WalletLifecyclePolicy {
    private static final int MAX_WATCH_ONLY = 2;

    private WalletLifecyclePolicy() {
    }

    public static void requireCapacity(WalletKind kind, long activeOrCreatingCount) {
        if (kind == null || activeOrCreatingCount < 0) {
            throw new WalletRuleViolation("Wallet kind and capacity are required.");
        }
        if (kind == WalletKind.WATCH_ONLY) {
            if (activeOrCreatingCount >= MAX_WATCH_ONLY) {
                throw new WalletRuleViolation("É permitido criar no máximo duas carteiras frias ativas.");
            }
            return;
        }
        if (activeOrCreatingCount > 0) {
            throw new WalletRuleViolation(
                    "Já existe uma carteira ativa ou em criação para este método de custódia.");
        }
    }

    public static void requireLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new WalletRuleViolation("Wallet label is required.");
        }
    }

    public static void requireUpdatable(WalletStatus status, String label) {
        if (status == WalletStatus.ARCHIVED) {
            throw new WalletRuleViolation("Archived wallets cannot be updated.");
        }
        requireLabel(label);
    }

    public static void requireArchivable(WalletStatus status) {
        if (status == WalletStatus.CREATING || status == WalletStatus.ROTATING_ADDRESS) {
            throw new WalletRuleViolation("Wallet cannot be archived while it is being created or rotated.");
        }
    }

    public static void requireRotatable(WalletStatus status) {
        if (status != WalletStatus.ACTIVE) {
            throw new WalletRuleViolation("Wallet is not active.");
        }
    }
}
