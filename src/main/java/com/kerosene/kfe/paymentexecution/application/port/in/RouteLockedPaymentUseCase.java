package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.RouteLockedPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentRoutingResult;

public interface RouteLockedPaymentUseCase {
    PaymentRoutingResult route(RouteLockedPaymentCommand command);
}
