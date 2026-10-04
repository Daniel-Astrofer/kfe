package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.RouteLockedPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.RouteLockedPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentRoutingResult;
import com.kerosene.kfe.paymentexecution.application.usecase.RouteLockedPaymentService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Scheduling, status and statements join the reservation and request link in the owning submit. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalPaymentRoutingAdapter implements RouteLockedPaymentUseCase {
    private final RouteLockedPaymentService service;
    public TransactionalPaymentRoutingAdapter(RouteLockedPaymentService service) { this.service = service; }
    @Override
    public PaymentRoutingResult route(RouteLockedPaymentCommand command) { return service.route(command); }
}
