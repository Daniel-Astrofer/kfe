package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

public interface CompletePaymentSubmissionUseCase {
    PaymentExecutionResult complete(CompletePaymentSubmissionCommand command);
}
