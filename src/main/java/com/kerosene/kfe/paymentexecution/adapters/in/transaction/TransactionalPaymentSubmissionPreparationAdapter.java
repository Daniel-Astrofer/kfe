package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PreparePaymentSubmissionUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentSubmission;
import com.kerosene.kfe.paymentexecution.application.usecase.PreparePaymentSubmissionService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalPaymentSubmissionPreparationAdapter implements PreparePaymentSubmissionUseCase {
    private final PreparePaymentSubmissionService service;
    public TransactionalPaymentSubmissionPreparationAdapter(PreparePaymentSubmissionService service) { this.service = service; }
    @Override public PreparedPaymentSubmission prepare(PreparePaymentSubmissionCommand command) { return service.prepare(command); }
}
