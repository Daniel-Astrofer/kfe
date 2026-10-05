package com.kerosene.kfe.paymentexecution.adapters.out.pricing;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentPricingPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentPricingQuote;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.pricing.application.port.out.PricingPolicyPort;
import com.kerosene.kfe.pricing.domain.model.SatoshiAmount;
import com.kerosene.kfe.pricing.domain.service.PricingCalculator;
import org.springframework.stereotype.Component;

/** Maps context-local inputs to the existing pricing calculator without re-estimating network fees. */
@Component
public class ContextPaymentPricingAdapter implements PaymentPricingPort {

    private final PricingPolicyPort policy;
    private final PricingCalculator calculator;

    public ContextPaymentPricingAdapter(PricingPolicyPort policy, PricingCalculator calculator) {
        this.policy = policy;
        this.calculator = calculator;
    }

    @Override
    public PaymentPricingQuote quote(PaymentRail rail, PaymentDirection direction, long amountSats, long networkFeeSats) {
        var pricingRail = com.kerosene.kfe.pricing.domain.model.PaymentRail.valueOf(rail.name());
        var pricingDirection = com.kerosene.kfe.pricing.domain.model.PaymentDirection.valueOf(direction.name());
        var quote = calculator.quote(
                pricingRail, pricingDirection, SatoshiAmount.positive(amountSats), new SatoshiAmount(networkFeeSats),
                policy.snapshot(pricingRail, pricingDirection));
        return new PaymentPricingQuote(
                quote.grossAmount().value(), quote.receiverAmount().value(), quote.networkFee().value(),
                quote.keroseneFee().value(), quote.totalDebit().value(), quote.pricingPolicyVersion());
    }
}
