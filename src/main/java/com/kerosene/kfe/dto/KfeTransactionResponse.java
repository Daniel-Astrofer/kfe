package com.kerosene.kfe.dto;

import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record KfeTransactionResponse(
        UUID id,
        KfeTransactionStatus status,
        /** Coarse UI badge: PENDING | CONFIRMED | FAILED — stable identity stays {@code id}. */
        String displayStatus,
        KfeRail rail,
        KfeDirection direction,
        UUID walletId,
        UUID sourceWalletId,
        UUID destinationWalletId,
        /** Human label of the perspective wallet (never a UUID). */
        String walletLabel,
        /** Human label of the source wallet when known. */
        String sourceWalletLabel,
        /** Human label of the destination wallet when known. */
        String destinationWalletLabel,
        /**
         * Safe counterparty label for the requesting user:
         * peer wallet name (internal), external address short form, or network rail name.
         */
        String counterpartyLabel,
        long grossAmountSats,
        long receiverAmountSats,
        long networkFeeSats,
        long keroseneFeeSats,
        long totalDebitSats,
        BigDecimal displayBtcUsd,
        BigDecimal displayBtcEur,
        BigDecimal displayBtcBrl,
        BigDecimal displayAmountUsd,
        BigDecimal displayAmountEur,
        BigDecimal displayAmountBrl,
        String quorumProposalHash,
        int quorumAckCount,
        String provider,
        String providerReference,
        String externalReference,
        String memo,
        String blockchainTxid,
        String paymentHash,
        int confirmations,
        String failureCode,
        String failureMessage,
        // Instant serializes as ISO-8601 with Z so clients convert timezone correctly.
        Instant createdAt,
        Instant updatedAt,
        /**
         * When true the client may show Cancel on transaction details
         * ({@code POST /kfe/transactions/{id}/cancel}).
         */
        boolean cancellable,
        /**
         * What cancel will target: {@code PAYMENT_REQUEST}, {@code TRANSACTION}, or null.
         */
        String cancelTarget,
        /** Linked invoice / payment link when known. */
        UUID paymentRequestId,
        String paymentRequestPublicId,
        String paymentRequestStatus) {
}
