package com.kerosene.kfe.paymentexecution.adapters.in.http;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** HTTP representation shared by submit, query and cancellation endpoints. */
public record PaymentExecutionResponse(
        UUID id,
        ExecutionStatus status,
        String displayStatus,
        String productStatus,
        PaymentRail rail,
        PaymentDirection direction,
        UUID walletId,
        UUID sourceWalletId,
        UUID destinationWalletId,
        String walletLabel,
        String sourceWalletLabel,
        String destinationWalletLabel,
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
        Instant createdAt,
        Instant updatedAt,
        boolean cancellable,
        String cancelTarget,
        UUID paymentRequestId,
        String paymentRequestPublicId,
        String paymentRequestStatus,
        String businessStatus,
        String networkStatus,
        String accountingStatus) {
}
