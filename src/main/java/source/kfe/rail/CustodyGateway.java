package source.kfe.rail;

import java.time.LocalDateTime;

public interface CustodyGateway extends LightningInvoiceGateway, LightningPaymentGateway {

    boolean isLive();

    String providerName();

    GeneratedOnchainAddress createOnchainAddress(OnchainAddressCommand command);

    GeneratedLightningInvoice createLightningInvoice(LightningInvoiceCommand command);

    IncomingLightningInvoiceStatus getLightningInvoiceStatus(LightningInvoiceStatusCommand command);

    boolean cancelLightningInvoice(LightningInvoiceCancellationCommand command);

    PaymentResult sendOnchain(OnchainPaymentCommand command);

    PaymentResult payLightning(LightningPaymentCommand command);

    record OnchainAddressCommand(
            Long userId,
            Long walletId,
            String walletName,
            String label) {
    }

    record GeneratedOnchainAddress(
            String address,
            String walletReference,
            String providerReference) {
    }

    record LightningInvoiceCommand(
            Long userId,
            Long walletId,
            String walletName,
            long amountSats,
            String memo,
            int expiresInSeconds) {
    }

    record GeneratedLightningInvoice(
            String paymentRequest,
            String paymentHash,
            String lightningAddress,
            String providerReference,
            LocalDateTime expiresAt) {
    }

    record LightningInvoiceStatusCommand(
            Long userId,
            Long walletId,
            String walletName,
            String paymentHash,
            String providerReference,
            String paymentRequest) {
    }

    record LightningInvoiceCancellationCommand(
            Long userId,
            Long walletId,
            String walletName,
            String paymentHash,
            String providerReference,
            String paymentRequest) {
    }

    record IncomingLightningInvoiceStatus(
            String status,
            Long receivedSats,
            LocalDateTime settledAt,
            String rawPayload) {
    }

    record OnchainPaymentCommand(
            Long userId,
            Long walletId,
            String walletName,
            String destinationAddress,
            long amountSats,
            String description,
            String idempotencyKey,
            String authorizationProof) {
    }

    record LightningPaymentCommand(
            Long userId,
            Long walletId,
            String walletName,
            String paymentRequest,
            long amountSats,
            long maxFeeSats,
            String description,
            String idempotencyKey,
            String authorizationProof) {
    }

    record PaymentResult(
            String providerReference,
            String txid,
            String paymentHash,
            String status,
            long feeSats,
            String rawPayload) {
    }
}
