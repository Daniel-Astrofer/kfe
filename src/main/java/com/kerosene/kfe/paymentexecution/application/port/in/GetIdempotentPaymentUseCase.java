package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import java.util.Optional;

public interface GetIdempotentPaymentUseCase {
    Optional<PaymentExecutionResult> find(GetIdempotentPaymentQuery query);
}
