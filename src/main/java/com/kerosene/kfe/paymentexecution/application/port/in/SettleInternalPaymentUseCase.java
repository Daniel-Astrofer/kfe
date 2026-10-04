package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.SettleInternalPaymentCommand;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;

public interface SettleInternalPaymentUseCase {
    PaymentExecutionStatusChanged settle(SettleInternalPaymentCommand command);
}
