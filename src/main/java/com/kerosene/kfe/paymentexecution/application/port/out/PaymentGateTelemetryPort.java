package com.kerosene.kfe.paymentexecution.application.port.out;

public interface PaymentGateTelemetryPort {
    void recordSettlementGate(boolean passed);
    void recordLiquidityReject(String reason);
    void recordSolvencyFailure(String reason);
}
