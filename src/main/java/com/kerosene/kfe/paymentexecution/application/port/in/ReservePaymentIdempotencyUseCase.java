package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentIdempotencyCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentIdempotencyReservationResult;

public interface ReservePaymentIdempotencyUseCase {
    PaymentIdempotencyReservationResult reserve(ReservePaymentIdempotencyCommand command);
}
