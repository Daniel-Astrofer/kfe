package com.kerosene.kfe.rail;

/**
 * Payment left LND in a non-terminal state — must not settle or permanently fail the reserve.
 * Outbox processor maps this to REQUIRES_RECONCILIATION / retry path.
 */
public class LightningPaymentInFlightException extends RuntimeException {

    private final String providerReference;
    private final String rawPayload;

    public LightningPaymentInFlightException(
            String message, String providerReference, String rawPayload) {
        super(message);
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
