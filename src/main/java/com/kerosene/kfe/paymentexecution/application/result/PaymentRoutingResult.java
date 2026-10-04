package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import java.util.UUID;

/** Only outbound work is eligible for the caller's optional post-commit immediate dispatch. */
public record PaymentRoutingResult(PaymentExecutionStatusChanged transition, PaymentRail rail, UUID immediateDispatchOutboxId) {}
