package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentFundsCommand;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;

public interface ReservePaymentFundsUseCase {
    PaymentExecutionStatusChanged reserve(ReservePaymentFundsCommand command);
}
