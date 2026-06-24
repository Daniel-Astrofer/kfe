package source.kfe.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
        String memo,
        String totpCode,
        String passkeyAssertionJson,
        String confirmationPassphrase,
        String appPin) {

    public KfeSubmitTransactionRequest withDestinationWalletId(UUID resolvedDestinationWalletId) {
        return new KfeSubmitTransactionRequest(
                idempotencyKey, rail, direction, sourceWalletId, resolvedDestinationWalletId, amountSats,
                networkFeeSats, externalReference, memo, totpCode, passkeyAssertionJson,
                confirmationPassphrase, appPin);
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
                externalReference, memo, totpCode, passkeyAssertionJson, confirmationPassphrase, null);
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
                externalReference, memo, null, null, null, null);
    }
}
