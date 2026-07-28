package com.kerosene.kfe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.kerosene.kfe.config.KfePricingPolicy;
import com.kerosene.kfe.config.KfePricingPolicy.RailPricing;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;

import static org.assertj.core.api.Assertions.assertThat;

class KfePricingServiceTest {

    private KfePricingService pricingService;

    @BeforeEach
    void setUp() {
        KfePricingPolicy policy = new KfePricingPolicy();
        policy.setVersion(1);

        RailPricing onchainOutbound = new RailPricing();
        onchainOutbound.setBasisPoints(90);
        onchainOutbound.setEnabled(true);

        RailPricing onchainInbound = new RailPricing();
        onchainInbound.setBasisPoints(0);
        onchainInbound.setEnabled(true);

        policy.getRails().put("ONCHAIN-OUTBOUND", onchainOutbound);
        policy.getRails().put("ONCHAIN-INBOUND", onchainInbound);

        pricingService = new KfePricingService(policy);
    }

    @Test
    void appliesFixedOnchainFeeToOutbound() {
        KfePricingService.Quote quote = pricingService.quote(
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                100_000L,
                1_000L);

        assertThat(quote.keroseneFeeSats()).isEqualTo(900L);
        assertThat(quote.receiverAmountSats()).isEqualTo(100_000L);
        assertThat(quote.totalDebitSats()).isEqualTo(101_900L);
        assertThat(quote.pricingPolicyVersion()).isEqualTo(1);
    }

    @Test
    void inboundHasZeroKeroseneFeePerConfig() {
        KfePricingService.Quote quote = pricingService.quote(
                KfeRail.ONCHAIN,
                KfeDirection.INBOUND,
                100_000L,
                0L);

        assertThat(quote.keroseneFeeSats()).isZero();
        assertThat(quote.receiverAmountSats()).isEqualTo(100_000L);
        assertThat(quote.totalDebitSats()).isZero();
    }

    @Test
    void keepsInternalTransfersFree() {
        KfePricingService.Quote quote = pricingService.quote(
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                100_000L,
                5_000L);

        assertThat(quote.keroseneFeeSats()).isZero();
        assertThat(quote.networkFeeSats()).isZero();
        assertThat(quote.totalDebitSats()).isEqualTo(100_000L);
    }
}
