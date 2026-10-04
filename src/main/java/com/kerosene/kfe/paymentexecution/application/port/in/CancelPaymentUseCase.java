package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

public interface CancelPaymentUseCase {
    PaymentExecutionResult cancel(CancelPaymentCommand command);
}
