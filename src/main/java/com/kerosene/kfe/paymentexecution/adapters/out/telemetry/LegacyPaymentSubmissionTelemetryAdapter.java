package com.kerosene.kfe.paymentexecution.adapters.out.telemetry;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionTelemetryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LegacyPaymentSubmissionTelemetryAdapter implements PaymentSubmissionTelemetryPort {
    private static final Logger log = LoggerFactory.getLogger(LegacyPaymentSubmissionTelemetryAdapter.class);

    @Override
    public void feeReserveRaised(long clientFeeSats, long reservedFeeSats, Long feeRateSatPerVbyte, Integer feeTargetBlocks) {
        log.warn("[KFE Submit] raising on-chain fee reserve clientFeeSats={} floorFeeSats={} feeRate={} targetBlocks={}",
                clientFeeSats, reservedFeeSats, feeRateSatPerVbyte, feeTargetBlocks);
    }
}
