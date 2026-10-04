package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationStatus;
import java.util.UUID;

/** Only the user-scoped request metadata needed to present cancellation eligibility. */
public record PaymentRequestCancellationReference(
        UUID id, long userId, String publicId, PaymentRequestCancellationStatus status) {}
