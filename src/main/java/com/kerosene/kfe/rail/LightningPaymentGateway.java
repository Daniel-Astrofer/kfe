package com.kerosene.kfe.rail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public interface LightningPaymentGateway {

    boolean isLive();

    String providerName();

    CustodyGateway.PaymentResult payLightning(CustodyGateway.LightningPaymentCommand command);

    /** Resolves all provider-independent request state before the provider can move funds. */
    default PreparedLightningPayment prepareLightning(CustodyGateway.LightningPaymentCommand command) {
        if (command == null || command.paymentRequest() == null || command.paymentRequest().isBlank()) {
            throw new IllegalArgumentException("Lightning payment command and destination are required.");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException(
                    "Provider-backed Lightning payments require an idempotency key.");
        }
        String reference = sha256(providerName() + "|" + command.idempotencyKey());
        return new PreparedLightningPayment(
                "PROVIDER_IDEMPOTENT",
                command.userId(),
                command.walletId(),
                command.walletName(),
                command.paymentRequest(),
                null,
                null,
                null,
                reference,
                command.amountSats(),
                command.maxFeeSats(),
                command.description(),
                command.idempotencyKey(),
                command.authorizationProof());
    }

    default CustodyGateway.PaymentResult payPreparedLightning(PreparedLightningPayment prepared) {
        if (prepared == null) {
            throw new IllegalArgumentException("Prepared Lightning payment is required.");
        }
        return payLightning(new CustodyGateway.LightningPaymentCommand(
                prepared.userId(),
                prepared.walletId(),
                prepared.walletName(),
                prepared.paymentRequest(),
                prepared.amountSats(),
                prepared.maxFeeSats(),
                prepared.description(),
                prepared.idempotencyKey(),
                prepared.authorizationProof()));
    }

    record PreparedLightningPayment(
            String destinationKind,
            Long userId,
            Long walletId,
            String walletName,
            String paymentRequest,
            String nodePubkey,
            String keysendPreimageBase64,
            String paymentHash,
            String executionReference,
            long amountSats,
            long maxFeeSats,
            String description,
            String idempotencyKey,
            String authorizationProof) {
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
