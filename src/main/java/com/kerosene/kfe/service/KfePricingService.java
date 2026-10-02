package com.kerosene.kfe.service;

import org.springframework.stereotype.Service;
import com.kerosene.kfe.config.KfePricingPolicy;
import com.kerosene.kfe.config.KfePricingPolicy.RailPricing;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;

@Service
public class KfePricingService {

    private static final long BPS_DENOMINATOR = 10_000L;

    private final KfePricingPolicy policy;

    public KfePricingService(KfePricingPolicy policy) {
        this.policy = policy;
    }

    public Quote quote(KfeRail rail, KfeDirection direction, long amountSats, long networkFeeSats) {
        if (amountSats <= 0) {
            throw new IllegalArgumentException("amountSats must be positive.");
        }
        if (networkFeeSats < 0) {
            throw new IllegalArgumentException("networkFeeSats must be non-negative.");
        }

        int policyVersion = policy.getVersion();

        if (rail == KfeRail.INTERNAL || direction == KfeDirection.INTERNAL) {
            return new Quote(amountSats, amountSats, 0L, amountSats, 0L, policyVersion);
        }

        String railKey = rail.name() + "-" + direction.name();
        RailPricing railPricing = policy.forRailDirection(railKey);

        long keroseneFee;
        if (railPricing != null && railPricing.getBasisPoints() > 0) {
            keroseneFee = percentageFee(amountSats, railPricing.getBasisPoints());
            if (railPricing.getMinSats() != null && keroseneFee < railPricing.getMinSats()) {
                keroseneFee = railPricing.getMinSats();
            }
            if (railPricing.getMaxSats() != null && keroseneFee > railPricing.getMaxSats()) {
                keroseneFee = railPricing.getMaxSats();
            }
        } else {
            keroseneFee = 0L;
        }

        if (direction == KfeDirection.INBOUND) {
            long receiverAmount = amountSats - keroseneFee;
            if (receiverAmount <= 0) {
                throw new IllegalArgumentException("Inbound amount is too small after Kerosene fee.");
            }
            return new Quote(amountSats, receiverAmount, networkFeeSats, 0L, keroseneFee, policyVersion);
        }

        long totalDebit = Math.addExact(amountSats, Math.addExact(networkFeeSats, keroseneFee));
        return new Quote(amountSats, amountSats, networkFeeSats, totalDebit, keroseneFee, policyVersion);
    }

    private long percentageFee(long amountSats, int basisPoints) {
        return Math.floorDiv(Math.addExact(Math.multiplyExact(amountSats, (long) basisPoints),
                BPS_DENOMINATOR - 1), BPS_DENOMINATOR);
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
