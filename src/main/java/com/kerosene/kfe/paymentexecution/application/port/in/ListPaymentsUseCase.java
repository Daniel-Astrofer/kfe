package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.query.ListPaymentsQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

import java.util.List;

public interface ListPaymentsUseCase {
    List<PaymentExecutionResult> list(ListPaymentsQuery query);
}
