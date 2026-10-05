package com.kerosene.kfe.wallet.domain.exception;

public final class WalletRuleViolation extends IllegalArgumentException {
    public WalletRuleViolation(String message) {
        super(message);
    }
}
