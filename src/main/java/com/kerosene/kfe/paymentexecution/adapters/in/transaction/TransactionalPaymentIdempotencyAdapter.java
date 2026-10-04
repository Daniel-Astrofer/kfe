package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentIdempotencyCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.GetIdempotentPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentIdempotencyUseCase;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.application.result.PaymentIdempotencyReservationResult;
import com.kerosene.kfe.paymentexecution.application.usecase.GetIdempotentPaymentService;
import com.kerosene.kfe.paymentexecution.application.usecase.ReservePaymentIdempotencyService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Component
public class TransactionalPaymentIdempotencyAdapter implements GetIdempotentPaymentUseCase, ReservePaymentIdempotencyUseCase {
    private final GetIdempotentPaymentService queries;
    private final ReservePaymentIdempotencyService reservations;
    public TransactionalPaymentIdempotencyAdapter(GetIdempotentPaymentService queries, ReservePaymentIdempotencyService reservations) {
        this.queries = queries;
        this.reservations = reservations;
    }
    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentExecutionResult> find(GetIdempotentPaymentQuery query) { return queries.find(query); }
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentIdempotencyReservationResult reserve(ReservePaymentIdempotencyCommand command) { return reservations.reserve(command); }
}
