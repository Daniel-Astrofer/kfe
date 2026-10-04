package com.kerosene.kfe.paymentexecution.adapters.out.pricing;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentNetworkFeeFloorPort;
import com.kerosene.kfe.pricing.adapters.out.bitcoin.KfeNetworkFeeEstimateService;
import org.springframework.stereotype.Component;

/** Keeps the existing rate/target-aware reservation floor, distinct from a public fee quote. */
@Component
public class BitcoinPaymentNetworkFeeFloorAdapter implements PaymentNetworkFeeFloorPort {

    private final KfeNetworkFeeEstimateService feeEstimateService;

    public BitcoinPaymentNetworkFeeFloorAdapter(KfeNetworkFeeEstimateService feeEstimateService) {
        this.feeEstimateService = feeEstimateService;
    }

    @Override
    public long minimumReserve(Long feeRateSatPerVbyte, Integer feeTargetBlocks) {
        return feeEstimateService.reservedFeeFloorSats(feeRateSatPerVbyte, feeTargetBlocks);
    }
}
