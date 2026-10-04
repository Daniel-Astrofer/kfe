package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;

public interface AuthorizePaymentUseCase {
    void authorize(SubmitPaymentCommand command);
}
