package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestLinkSnapshot;

/** Internal continuity snapshot, not a transport capability or a substitute for authorization. */
public record PreparedPaymentRequestLink(long payerUserId, PaymentRequestLinkSnapshot request, long amountSats) {
    public PreparedPaymentRequestLink {
        if (payerUserId <= 0L || request == null || amountSats <= 0L) {
            throw new IllegalArgumentException("payer, request and positive amount are required");
        }
    }
}
