package com.kerosene.kfe.paymentexecution.application.port.out;

/** Provides the authoritative fee reserve floor for an outbound on-chain payment. */
public interface PaymentNetworkFeeFloorPort {

    long minimumReserve(Long feeRateSatPerVbyte, Integer feeTargetBlocks);
}
