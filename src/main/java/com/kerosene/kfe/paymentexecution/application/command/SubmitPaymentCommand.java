package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.util.UUID;

/** Transport-independent request to submit a payment for execution. */
public record SubmitPaymentCommand(
        long userId,
        IdempotencyKey idempotencyKey,
        PaymentRail rail,
        PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amountSats,
        long networkFeeSats,
        String externalReference,
        String memo,
        String totpCode,
        String passkeyAssertionJson,
        String confirmationPassphrase,
        String appPin,
        String paymentRequestPublicId,
        Long feeRateSatPerVbyte,
        Integer feeTargetBlocks,
        String quoteId,
        String deviceHash) {

    public SubmitPaymentCommand {
        if (userId <= 0L) {
            throw new IllegalArgumentException("authenticated user id must be positive");
        }
        if (idempotencyKey == null || rail == null || direction == null) {
            throw new IllegalArgumentException("idempotency key, rail and direction are required");
        }
        if (amountSats <= 0L || networkFeeSats < 0L) {
            throw new IllegalArgumentException("payment amount must be positive and network fee non-negative");
        }
    }

    public SubmitPaymentCommand withDestinationWalletId(UUID destination) {
        return new SubmitPaymentCommand(userId, idempotencyKey, rail, direction, sourceWalletId,
                destination, amountSats, networkFeeSats, externalReference, memo, totpCode,
                passkeyAssertionJson, confirmationPassphrase, appPin, paymentRequestPublicId,
                feeRateSatPerVbyte, feeTargetBlocks, quoteId, deviceHash);
    }

    public SubmitPaymentCommand withCanonicalDestination(String reference, String canonicalMemo) {
        return new SubmitPaymentCommand(userId, idempotencyKey, rail, direction, sourceWalletId,
                destinationWalletId, amountSats, networkFeeSats, reference, canonicalMemo, totpCode,
                passkeyAssertionJson, confirmationPassphrase, appPin, paymentRequestPublicId,
                feeRateSatPerVbyte, feeTargetBlocks, quoteId, deviceHash);
    }

    @Override
    public String toString() {
        return "SubmitPaymentCommand[userId=" + userId
                + ", idempotencyKey=" + idempotencyKey
                + ", rail=" + rail
                + ", direction=" + direction
                + ", sourceWalletId=" + sourceWalletId
                + ", destinationWalletId=" + destinationWalletId
                + ", amountSats=" + amountSats
                + ", networkFeeSats=" + networkFeeSats
                + ", authorizationFactors=REDACTED]";
    }
}
