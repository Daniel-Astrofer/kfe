package com.kerosene.kfe.paymentexecution.domain.model;

/** Ledger-cached balances, not proof of live spendable reserve assets. */
public record SettlementBalanceSnapshot(SettlementWalletRole role, long availableSats, long pendingSats,
        long lockedSats, long autoHoldSats, long observedSats) {}
