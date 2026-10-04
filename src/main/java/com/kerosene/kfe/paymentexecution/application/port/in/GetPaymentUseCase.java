package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.query.GetPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

public interface GetPaymentUseCase {
    PaymentExecutionResult get(GetPaymentQuery query);
}
