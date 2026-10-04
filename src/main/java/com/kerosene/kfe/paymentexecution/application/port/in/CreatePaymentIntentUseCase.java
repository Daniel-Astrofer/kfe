package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.CreatePaymentIntentCommand;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Creates the initial intent inside the caller's submission transaction, after idempotency reservation. */
public interface CreatePaymentIntentUseCase {

    PaymentExecutionId create(CreatePaymentIntentCommand command);
}
