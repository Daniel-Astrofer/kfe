package com.kerosene.kfe.pricing.adapters.in.compatibility;

import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.pricing.application.port.out.PricingPolicyPort;
import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;
import com.kerosene.kfe.pricing.domain.model.PricingQuote;
import com.kerosene.kfe.pricing.domain.model.SatoshiAmount;
import com.kerosene.kfe.pricing.domain.service.PricingCalculator;

@Service
public class KfePricingService {

    private final PricingPolicyPort policy;
    private final PricingCalculator calculator;

    public KfePricingService(PricingPolicyPort policy, PricingCalculator calculator) {
        this.policy = policy;
        this.calculator = calculator;
    }

    public Quote quote(KfeRail rail, KfeDirection direction, long amountSats, long networkFeeSats) {
        PaymentRail domainRail = PaymentRail.valueOf(rail.name());
        PaymentDirection domainDirection = PaymentDirection.valueOf(direction.name());
        PricingQuote result = calculator.quote(
                domainRail,
                domainDirection,
                SatoshiAmount.positive(amountSats),
                new SatoshiAmount(networkFeeSats),
                policy.snapshot(domainRail, domainDirection));
        return new Quote(
                result.grossAmount().value(),
                result.receiverAmount().value(),
                result.networkFee().value(),
                result.totalDebit().value(),
                result.keroseneFee().value(),
                result.pricingPolicyVersion());
    }

    public record Quote(
            long grossAmountSats,
            long receiverAmountSats,
            long networkFeeSats,
            long totalDebitSats,
            long keroseneFeeSats,
            int pricingPolicyVersion) {

        public Quote(long grossAmountSats, long receiverAmountSats, long networkFeeSats, long totalDebitSats) {
            this(grossAmountSats, receiverAmountSats, networkFeeSats, totalDebitSats, 0L, 0);
        }

        public Quote(long grossAmountSats, long receiverAmountSats, long networkFeeSats, long totalDebitSats, long keroseneFeeSats) {
            this(grossAmountSats, receiverAmountSats, networkFeeSats, totalDebitSats, keroseneFeeSats, 0);
        }
    }
}
