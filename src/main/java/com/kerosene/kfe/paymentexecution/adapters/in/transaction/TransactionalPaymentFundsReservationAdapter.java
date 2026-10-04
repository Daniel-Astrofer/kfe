package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentFundsCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentFundsUseCase;
import com.kerosene.kfe.paymentexecution.application.usecase.ReservePaymentFundsService;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Authorization, gate, reservation and subsequent routing must remain in the caller's commit. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalPaymentFundsReservationAdapter implements ReservePaymentFundsUseCase {
    private final ReservePaymentFundsService service;

    public TransactionalPaymentFundsReservationAdapter(ReservePaymentFundsService service) { this.service = service; }

    @Override
    public PaymentExecutionStatusChanged reserve(ReservePaymentFundsCommand command) { return service.reserve(command); }
}
