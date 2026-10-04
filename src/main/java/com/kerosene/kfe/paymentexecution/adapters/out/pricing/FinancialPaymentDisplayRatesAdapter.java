package com.kerosene.kfe.paymentexecution.adapters.out.pricing;

import com.kerosene.common.financial.operations.FinancialTickerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDisplayRatesPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentDisplayRates;
import org.springframework.stereotype.Component;

/** Captures the three historical display rates in their original lookup order; provider failures propagate. */
@Component
public class FinancialPaymentDisplayRatesAdapter implements PaymentDisplayRatesPort {

    private final FinancialTickerPort ticker;

    public FinancialPaymentDisplayRatesAdapter(FinancialTickerPort ticker) {
        this.ticker = ticker;
    }

    @Override
    public PaymentDisplayRates currentRates() {
        return new PaymentDisplayRates(ticker.getPrice("usd"), ticker.getPrice("eur"), ticker.getPrice("brl"));
    }
}
