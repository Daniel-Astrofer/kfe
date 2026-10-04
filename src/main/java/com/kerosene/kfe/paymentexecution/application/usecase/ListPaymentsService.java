package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.in.ListPaymentsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.application.query.ListPaymentsQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

import java.util.List;

public final class ListPaymentsService implements ListPaymentsUseCase {

    private final PaymentExecutionQueryRepository repository;

    public ListPaymentsService(PaymentExecutionQueryRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<PaymentExecutionResult> list(ListPaymentsQuery query) {
        int safePage = Math.max(0, query.page());
        int safeSize = Math.min(200, Math.max(1, query.size()));
        return repository.findParticipantVisible(query.userId(), safePage, safeSize, query.since());
    }
}
