package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

public interface SubmitPaymentUseCase {
    PaymentExecutionResult submit(SubmitPaymentCommand command);
}
