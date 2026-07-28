package com.kerosene.kfe.dto;

import com.kerosene.kfe.model.KfePaymentRequestStatus;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record KfePaymentRequestResponse(
        UUID id,
        String publicId,
        Long userId,
        UUID walletId,
        UUID addressId,
        /** Legacy: primary on-chain address or ln:hash. */
        String address,
        /** BOLT11 invoice when primary rail is LIGHTNING. */
        String paymentRequest,
        String paymentHash,
        /** Legacy: primary rail. */
        KfeRail rail,
        /** All active rails with their respective receiving payloads. */
        List<RailDetail> rails,
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
        Instant updatedAt,
        /** Payment behavior contract snapshot. */
        String behaviorContract,
        /** Cumulative sats received on open-amount links. */
        Long partialPaymentReceived,
        /** Optional webhook URL configured for this payment request. */
        String webhookUrl) {

        public KfePaymentRequestResponse {
                rails = rails == null || rails.isEmpty() ? List.of() : List.copyOf(rails);
        }
}
