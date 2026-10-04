package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.PaymentPricingQuote;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

/** Anti-corruption boundary: pricing context types do not enter Payment Execution's application. */
public interface PaymentPricingPort {

    PaymentPricingQuote quote(PaymentRail rail, PaymentDirection direction, long amountSats, long networkFeeSats);
}
