package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentRequestCommand;
import java.util.UUID;

public interface CancelPaymentRequestUseCase {
    UUID cancelPaymentRequest(CancelPaymentRequestCommand command);
}
