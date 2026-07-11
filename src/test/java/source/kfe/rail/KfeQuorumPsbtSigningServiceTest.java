package source.kfe.rail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeQuorumPsbtSigningServiceTest {

    private final BitcoinCoreRpcClient bitcoinCore = mock(BitcoinCoreRpcClient.class);
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider = provider(bitcoinCore);
    private final KfeQuorumPsbtSigningService service = new KfeQuorumPsbtSigningService(
            bitcoinCoreProvider,
            mock(RestTemplate.class),
            new ObjectMapper(),
            1,
            6,
            "http://signer-one",
            "api-key",
            "signer-one",
            true);

    @Test
    void rejectsFundedPsbtBeforeSigningWhenActualFeeExceedsReservedLimit() {
        when(bitcoinCore.createFundedPsbt("bcrt1qdestination", 100_000L, 6))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("funded-psbt", 500L));

        assertThatThrownBy(() -> service.preflight(command(499L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Funded PSBT fee exceeds configured on-chain fee cap.");
    }

    @Test
    void acceptsFundedPsbtWhenActualFeeFitsReservedLimit() {
        when(bitcoinCore.createFundedPsbt("bcrt1qdestination", 100_000L, 6))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("funded-psbt", 500L));

        var preflight = service.preflight(command(500L));

        assertThat(preflight.feeSats()).isEqualTo(500L);
        assertThat(preflight.configuredSignerCount()).isEqualTo(1);
    }

    private KfeOnchainPaymentGateway.OnchainPreflightCommand command(long maxFeeSats) {
        return new KfeOnchainPaymentGateway.OnchainPreflightCommand(
                42L,
                null,
                "wallet",
                "bcrt1qdestination",
                100_000L,
                maxFeeSats,
                "idempotency-key");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
