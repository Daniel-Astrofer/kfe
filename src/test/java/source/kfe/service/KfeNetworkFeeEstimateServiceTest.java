package source.kfe.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.rail.BitcoinCoreRpcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeNetworkFeeEstimateServiceTest {

    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider = mock(ObjectProvider.class);

    @Test
    void usesServerRatesForOnchainOutboundAndReturnsThreeTiers() {
        BitcoinCoreRpcClient bitcoinCore = mock(BitcoinCoreRpcClient.class);
        when(bitcoinCoreProvider.getIfAvailable()).thenReturn(bitcoinCore);
        when(bitcoinCore.estimateSmartFeeRateSatPerVbyte(2)).thenReturn(8L);
        when(bitcoinCore.estimateSmartFeeRateSatPerVbyte(3)).thenReturn(5L);
        when(bitcoinCore.estimateSmartFeeRateSatPerVbyte(6)).thenReturn(2L);
        KfeNetworkFeeEstimateService service = service();

        KfeNetworkFeeEstimateService.Estimate estimate = service.estimate(
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                1L);

        assertThat(estimate.selectedNetworkFeeSats()).isEqualTo(1_000L);
        assertThat(estimate.selectedFeeRateSatPerVbyte()).isEqualTo(5L);
        assertThat(estimate.selectedTargetBlocks()).isEqualTo(3);
        assertThat(estimate.selectedEstimatedSeconds()).isEqualTo(1_800L);
        assertThat(estimate.selectedSource()).isEqualTo(KfeNetworkFeeEstimateService.BITCOIN_CORE_SOURCE);
        assertThat(estimate.tiers())
                .extracting(tier -> tier.priority() + ":" + tier.networkFeeSats())
                .containsExactly("FAST:1600", "STANDARD:1000", "SLOW:400");
    }

    @Test
    void identifiesConfiguredFallbackWhenBitcoinCoreHasNoEstimate() {
        when(bitcoinCoreProvider.getIfAvailable()).thenReturn(null);

        KfeNetworkFeeEstimateService.Estimate estimate = service().estimate(
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                0L);

        assertThat(estimate.selectedNetworkFeeSats()).isEqualTo(2_400L);
        assertThat(estimate.selectedSource()).isEqualTo(KfeNetworkFeeEstimateService.FALLBACK_SOURCE);
        assertThat(estimate.tiers()).allSatisfy(tier ->
                assertThat(tier.source()).isEqualTo(KfeNetworkFeeEstimateService.FALLBACK_SOURCE));
    }

    @Test
    void internalQuoteHasNoNetworkFeeOrSyntheticEta() {
        KfeNetworkFeeEstimateService.Estimate estimate = service().estimate(
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                50_000L);

        assertThat(estimate.selectedNetworkFeeSats()).isZero();
        assertThat(estimate.selectedEstimatedSeconds()).isZero();
        assertThat(estimate.selectedSource()).isEqualTo(KfeNetworkFeeEstimateService.NOT_APPLICABLE_SOURCE);
        assertThat(estimate.tiers()).isEmpty();
    }

    private KfeNetworkFeeEstimateService service() {
        return new KfeNetworkFeeEstimateService(
                bitcoinCoreProvider,
                200,
                2,
                3,
                6,
                25,
                12,
                6,
                600,
                120,
                "mainnet");
    }
}
