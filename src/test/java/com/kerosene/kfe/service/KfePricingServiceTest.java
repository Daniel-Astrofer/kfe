package com.kerosene.kfe.service;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;

import static org.assertj.core.api.Assertions.assertThat;

class KfePricingServiceTest {

    private final KfePricingService pricingService = new KfePricingService();

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
    }

    @Test
    void appliesFixedOnchainFeeToInboundNetCredit() {
        KfePricingService.Quote quote = pricingService.quote(
                KfeRail.ONCHAIN,
                KfeDirection.INBOUND,
                100_000L,
                0L);

        assertThat(quote.keroseneFeeSats()).isEqualTo(900L);
        assertThat(quote.receiverAmountSats()).isEqualTo(99_100L);
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
