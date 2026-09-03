package com.kerosene.kfe.service;

import org.springframework.stereotype.Service;
import com.kerosene.kfe.config.KfePricingPolicy;
import com.kerosene.kfe.config.KfePricingPolicy.RailPricing;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.domain.pricing.KfePricingCalculator;
import com.kerosene.kfe.domain.pricing.PricingPolicySnapshot;

@Service
public class KfePricingService {

    private final KfePricingPolicy policy;
    private final KfePricingCalculator calculator = new KfePricingCalculator();

    public KfePricingService(KfePricingPolicy policy) {
        this.policy = policy;
    }

    public Quote quote(KfeRail rail, KfeDirection direction, long amountSats, long networkFeeSats) {
        String railKey = rail.name() + "-" + direction.name();
        RailPricing configured = policy.forRailDirection(railKey);
        PricingPolicySnapshot snapshot = new PricingPolicySnapshot(policy.getVersion(),
                configured == null ? null : new PricingPolicySnapshot.RailPricing(
                        configured.getBasisPoints(), configured.getMinSats(), configured.getMaxSats()));
        KfePricingCalculator.Quote result = calculator.quote(rail, direction, amountSats, networkFeeSats, snapshot);
        return new Quote(result.grossAmountSats(), result.receiverAmountSats(), result.networkFeeSats(),
                result.totalDebitSats(), result.keroseneFeeSats(), result.pricingPolicyVersion());
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
