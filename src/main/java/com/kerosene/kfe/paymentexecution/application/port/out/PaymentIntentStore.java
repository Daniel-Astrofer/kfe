package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentIntent;

/** Initial persistence only; lifecycle audit and subsequent financial effects remain the caller's responsibility. */
public interface PaymentIntentStore {

    PaymentExecutionId create(PaymentIntent intent);
}
