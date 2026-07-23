package source.kfe.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;

import java.util.UUID;

public record KfeSubmitTransactionRequest(
        @NotBlank String idempotencyKey,
        @NotNull KfeRail rail,
        @NotNull KfeDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        @Min(1) long amountSats,
        @Min(0) long networkFeeSats,
        String externalReference,
        @Size(max = 255)
        String memo,
        String totpCode,
        String passkeyAssertionJson,
        String confirmationPassphrase,
        String appPin,
        @Size(max = 48)
        String paymentRequestPublicId,
        /** Explicit sat/vB from fee tier (FAST/STANDARD/SLOW). Used for on-chain PSBT funding. */
        Long feeRateSatPerVbyte,
        /** Confirmation target blocks for the selected fee tier (e.g. FAST=2). */
        Integer feeTargetBlocks) {

    public KfeSubmitTransactionRequest withDestinationWalletId(UUID resolvedDestinationWalletId) {
        return new KfeSubmitTransactionRequest(
                idempotencyKey, rail, direction, sourceWalletId, resolvedDestinationWalletId, amountSats,
                networkFeeSats, externalReference, memo, totpCode, passkeyAssertionJson,
                confirmationPassphrase, appPin, paymentRequestPublicId, feeRateSatPerVbyte, feeTargetBlocks);
    }

    /** Rewrites only the on-chain destination address (platform routing to custodial/cold). */
    public KfeSubmitTransactionRequest withExternalReference(String resolvedExternalReference) {
        return new KfeSubmitTransactionRequest(
                idempotencyKey, rail, direction, sourceWalletId, destinationWalletId, amountSats,
                networkFeeSats, resolvedExternalReference, memo, totpCode, passkeyAssertionJson,
                confirmationPassphrase, appPin, paymentRequestPublicId, feeRateSatPerVbyte, feeTargetBlocks);
    }

    public KfeSubmitTransactionRequest withMemo(String resolvedMemo) {
        return new KfeSubmitTransactionRequest(
                idempotencyKey, rail, direction, sourceWalletId, destinationWalletId, amountSats,
                networkFeeSats, externalReference, resolvedMemo, totpCode, passkeyAssertionJson,
                confirmationPassphrase, appPin, paymentRequestPublicId, feeRateSatPerVbyte, feeTargetBlocks);
    }

    public KfeSubmitTransactionRequest(
            String idempotencyKey,
            KfeRail rail,
            KfeDirection direction,
            UUID sourceWalletId,
            UUID destinationWalletId,
            long amountSats,
            long networkFeeSats,
            String externalReference,
            String memo,
            String totpCode,
            String passkeyAssertionJson,
            String confirmationPassphrase) {
        this(idempotencyKey, rail, direction, sourceWalletId, destinationWalletId, amountSats, networkFeeSats,
                externalReference, memo, totpCode, passkeyAssertionJson, confirmationPassphrase, null, null,
                null, null);
    }

    /** Tests / callers that set paymentRequestPublicId without fee-tier fields. */
    public KfeSubmitTransactionRequest(
            String idempotencyKey,
            KfeRail rail,
            KfeDirection direction,
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
            String paymentRequestPublicId) {
        this(idempotencyKey, rail, direction, sourceWalletId, destinationWalletId, amountSats, networkFeeSats,
                externalReference, memo, totpCode, passkeyAssertionJson, confirmationPassphrase, appPin,
                paymentRequestPublicId, null, null);
    }

    public KfeSubmitTransactionRequest(
            String idempotencyKey,
            KfeRail rail,
            KfeDirection direction,
            UUID sourceWalletId,
            UUID destinationWalletId,
            long amountSats,
            long networkFeeSats,
            String externalReference,
            String memo) {
        this(idempotencyKey, rail, direction, sourceWalletId, destinationWalletId, amountSats, networkFeeSats,
                externalReference, memo, null, null, null, null, null, null, null);
    }
}
