package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.SettlementJammingCheck;

public interface PaymentGateLightningPort {
    boolean isLive();
    long freeOutboundCapacitySats();
    boolean canCoverOutbound(long totalDebitSats);
    boolean circuitBreakerOpen();
    SettlementJammingCheck evaluateJamming();
}
