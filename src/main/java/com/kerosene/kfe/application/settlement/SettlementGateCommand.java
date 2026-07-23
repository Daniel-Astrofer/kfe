package com.kerosene.kfe.application.settlement;

import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;

import java.util.UUID;

/**
 * Immutable inputs for the binary settlement gate.
 * Amounts are always satoshis ({@code long}).
 */
public record SettlementGateCommand(
        Long userId,
        UUID transactionId,
        UUID sourceWalletId,
        String idempotencyKey,
        boolean idempotencyReserved,
        KfeRail rail,
        KfeDirection direction,
        long amountSats,
        long networkFeeSats,
        long totalDebitSats,
        boolean requiresSourceReserve,
        String proposalHash) {
}
