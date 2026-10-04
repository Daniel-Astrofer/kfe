package com.kerosene.kfe.paymentexecution.adapters.in.http;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** HTTP representation kept compatible with the existing transaction submission contract. */
public record SubmitPaymentRequest(
        @NotBlank String idempotencyKey,
        @NotNull PaymentRail rail,
        @NotNull PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        @Min(1) long amountSats,
        @Min(0) long networkFeeSats,
        String externalReference,
        @Size(max = 255) String memo,
        String totpCode,
        String passkeyAssertionJson,
        String confirmationPassphrase,
        String appPin,
        @Size(max = 48) String paymentRequestPublicId,
        Long feeRateSatPerVbyte,
        Integer feeTargetBlocks,
        String quoteId) {

    @Override
    public String toString() {
        return "SubmitPaymentRequest[idempotencyKey=" + idempotencyKey
                + ", rail=" + rail
                + ", direction=" + direction
                + ", sourceWalletId=" + sourceWalletId
                + ", destinationWalletId=" + destinationWalletId
                + ", amountSats=" + amountSats
                + ", networkFeeSats=" + networkFeeSats
                + ", authorizationFactors=REDACTED]";
    }
}
