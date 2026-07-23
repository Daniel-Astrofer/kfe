package com.kerosene.kfe.dto;

import com.kerosene.kfe.model.KfePaymentRequestStatus;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionStatus;

import java.time.Instant;
import java.util.UUID;

public record KfePaymentRequestResponse(
        UUID id,
        String publicId,
        Long userId,
        UUID walletId,
        UUID addressId,
        String address,
        /** BOLT11 invoice when rail is LIGHTNING. */
        String paymentRequest,
        String paymentHash,
        KfeRail rail,
        KfePaymentRequestStatus status,
        Long amountSats,
        String description,
        String memo,
        String payerHint,
        UUID paidTransactionId,
        UUID settlementTransactionId,
        KfeTransactionStatus settlementStatus,
        String blockchainTxid,
        Integer confirmations,
        Long grossAmountSats,
        Long receiverAmountSats,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {
}
