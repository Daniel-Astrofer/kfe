package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.in.GetPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.application.query.GetPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

public final class GetPaymentService implements GetPaymentUseCase {

    private final PaymentExecutionQueryRepository repository;

    public GetPaymentService(PaymentExecutionQueryRepository repository) {
        this.repository = repository;
    }

    @Override
    public PaymentExecutionResult get(GetPaymentQuery query) {
        return repository.findParticipantVisibleById(query.userId(), query.paymentExecutionId())
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
    }
}
