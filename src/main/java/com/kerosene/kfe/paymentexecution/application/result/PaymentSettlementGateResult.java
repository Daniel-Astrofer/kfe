package com.kerosene.kfe.paymentexecution.application.result;

/** Quorum evidence after the settlement gate has required all configured checks to pass. */
public record PaymentSettlementGateResult(int quorumAckCount, int quorumHealthyNodes) {
}
