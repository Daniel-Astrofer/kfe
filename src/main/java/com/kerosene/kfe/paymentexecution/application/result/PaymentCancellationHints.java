package com.kerosene.kfe.paymentexecution.application.result;

import java.util.UUID;

/** Preliminary UI indication, never a substitute for action authorization or the batch fence. */
public record PaymentCancellationHints(
        boolean cancellable, String cancelTarget, UUID paymentRequestId,
        String paymentRequestPublicId, String paymentRequestStatus) {
    public static final String PAYMENT_REQUEST = "PAYMENT_REQUEST";
    public static final String TRANSACTION = "TRANSACTION";

    public static PaymentCancellationHints none() {
        return new PaymentCancellationHints(false, null, null, null, null);
    }
}
