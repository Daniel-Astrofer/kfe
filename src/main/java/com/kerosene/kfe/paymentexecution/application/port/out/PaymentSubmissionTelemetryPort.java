package com.kerosene.kfe.paymentexecution.application.port.out;

public interface PaymentSubmissionTelemetryPort {
    void feeReserveRaised(long clientFeeSats, long reservedFeeSats, Long feeRateSatPerVbyte, Integer feeTargetBlocks);
}
