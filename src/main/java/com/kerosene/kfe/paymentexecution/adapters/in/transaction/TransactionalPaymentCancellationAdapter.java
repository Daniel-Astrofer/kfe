package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CancelPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.CancelPaymentRequestUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.application.usecase.CancelPaymentService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Owns the transaction spanning request lock, outbox fence and all local financial effects. */
@Component
@Transactional
public class TransactionalPaymentCancellationAdapter implements CancelPaymentUseCase, CancelPaymentRequestUseCase {
    private final CancelPaymentService service;

    public TransactionalPaymentCancellationAdapter(CancelPaymentService service) {
        this.service = service;
    }

    @Override
    public PaymentExecutionResult cancel(CancelPaymentCommand command) {
        return service.cancel(command);
    }

    @Override
    public UUID cancelPaymentRequest(CancelPaymentRequestCommand command) {
        return service.cancelPaymentRequest(command);
    }
}
