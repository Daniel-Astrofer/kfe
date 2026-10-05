package com.kerosene.kfe.pricing.application.port.out;

import com.kerosene.kfe.pricing.application.result.FeeTierResult;
import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;

import java.time.Instant;
import java.util.List;

public interface NetworkFeePort {

    NetworkFeeEstimate estimate(
            PaymentRail rail,
            PaymentDirection direction,
            long requestedNetworkFeeSats);

    record NetworkFeeEstimate(
            long selectedNetworkFeeSats,
            long selectedFeeRateSatPerVbyte,
            int selectedTargetBlocks,
            long selectedEstimatedSeconds,
            String selectedSource,
            int estimatedVbytes,
            Instant expiresAt,
            List<FeeTierResult> tiers) {

        public NetworkFeeEstimate {
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
        }
    }
}
