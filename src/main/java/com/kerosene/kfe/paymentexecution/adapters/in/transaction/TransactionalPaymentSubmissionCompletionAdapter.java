package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CompletePaymentSubmissionUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.application.usecase.CompletePaymentSubmissionService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalPaymentSubmissionCompletionAdapter implements CompletePaymentSubmissionUseCase {
    private final CompletePaymentSubmissionService service;
    public TransactionalPaymentSubmissionCompletionAdapter(CompletePaymentSubmissionService service) { this.service = service; }
    @Override public PaymentExecutionResult complete(CompletePaymentSubmissionCommand command) { return service.complete(command); }
}
