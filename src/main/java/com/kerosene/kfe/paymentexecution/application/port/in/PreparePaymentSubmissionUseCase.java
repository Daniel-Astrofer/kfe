package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentSubmission;

public interface PreparePaymentSubmissionUseCase {
    PreparedPaymentSubmission prepare(PreparePaymentSubmissionCommand command);
}
