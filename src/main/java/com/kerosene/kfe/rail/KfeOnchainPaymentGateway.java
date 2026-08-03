package com.kerosene.kfe.rail;

import java.util.List;

public interface KfeOnchainPaymentGateway {

    String providerName();

    default OnchainFundingPreflight preflightOnchain(OnchainPreflightCommand command) {
        return null;
    }

    PaymentResult sendOnchain(OnchainPaymentCommand command);

    default PreparedOnchainPayment prepareOnchain(OnchainPaymentCommand command) {
        throw new UnsupportedOperationException(
                "This on-chain provider does not support durable prepare-before-broadcast.");
    }

    default PaymentResult broadcastPrepared(PreparedOnchainPayment prepared) {
        throw new UnsupportedOperationException(
                "This on-chain provider does not support durable prepared broadcasts.");
    }

    default void releasePrepared(PreparedOnchainPayment prepared) {
        // Providers that lock external resources during preparation override this hook.
    }

    record OnchainPreflightCommand(
            Long userId,
            Long walletId,
            String walletName,
            String destinationAddress,
            long amountSats,
            long maxFeeSats,
            String idempotencyKey) {
    }

    record OnchainPaymentCommand(
            Long userId,
            Long walletId,
            String walletName,
            String destinationAddress,
            long amountSats,
            long maxFeeSats,
            String description,
            String idempotencyKey,
            String authorizationProof,
            /** Explicit sat/vB from the user fee tier; preferred over conf_target when &gt; 0. */
            Long feeRateSatsPerVbyte,
            /** Confirmation target blocks for Core estimatesmartfee path when no explicit rate. */
            Integer confirmationTarget) {

        /** Back-compat for callers that only pass max fee. */
        public OnchainPaymentCommand(
                Long userId,
                Long walletId,
                String walletName,
                String destinationAddress,
                long amountSats,
                long maxFeeSats,
                String description,
                String idempotencyKey,
                String authorizationProof) {
            this(
                    userId,
                    walletId,
                    walletName,
                    destinationAddress,
                    amountSats,
                    maxFeeSats,
                    description,
                    idempotencyKey,
                    authorizationProof,
                    null,
                    null);
        }
    }

    record OnchainFundingPreflight(
            boolean available,
            long feeSats,
            String psbtHash,
            int configuredSignerCount,
            String providerReference) {
    }

    record PreparedOnchainPayment(
            String rawTransaction,
            String expectedTxid,
            long feeSats,
            String fundedPsbtHash,
            String combinedPsbtHash,
            String rawTransactionHash,
            List<String> acceptedSigners,
            String intentId,
            String metadataJson) {

        public PreparedOnchainPayment {
            acceptedSigners = acceptedSigners == null ? List.of() : List.copyOf(acceptedSigners);
        }
    }

    record PaymentResult(
            String providerReference,
            String txid,
            String paymentHash,
            String status,
            long feeSats,
            String rawPayload) {
    }

    class ProviderExecutionAmbiguous extends RuntimeException {

        private final String providerReference;
        private final String rawPayload;

        public ProviderExecutionAmbiguous(
                String message,
                String providerReference,
                String rawPayload,
                Throwable cause) {
            super(message, cause);
            this.providerReference = providerReference;
            this.rawPayload = rawPayload;
        }

        public String providerReference() {
            return providerReference;
        }

        public String rawPayload() {
            return rawPayload;
        }
    }
}
