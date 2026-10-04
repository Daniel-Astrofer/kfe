package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentPreflightResult;

public interface PreflightPaymentUseCase {
    PaymentPreflightResult preflight(SubmitPaymentCommand command);
}
