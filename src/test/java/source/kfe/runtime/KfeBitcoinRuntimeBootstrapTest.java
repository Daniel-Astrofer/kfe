package source.kfe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import source.kfe.rail.BitcoinCoreRpcClient;
import source.kfe.service.KfeSystemWalletService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeBitcoinRuntimeBootstrapTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void validatesBitcoinCoreNetworkAndLoadsConfiguredWallets() {
        KfeSystemWalletService systemWalletService = mock(KfeSystemWalletService.class);
        BitcoinCoreRpcClient bitcoinCore = mock(BitcoinCoreRpcClient.class);
        when(systemWalletService.ensureSystemWallets()).thenReturn(wallets());
        when(bitcoinCore.chain()).thenReturn("test");
        when(bitcoinCore.blockchainInfo()).thenReturn(blockchainInfo(false));

        KfeBitcoinRuntimeBootstrap bootstrap = new KfeBitcoinRuntimeBootstrap(
                systemWalletService,
                provider(bitcoinCore),
                true,
                true,
                true,
                false,
                true,
                "testnet",
                "kerosene",
                "kerosene-funds",
                "kerosene-profit");

        bootstrap.run(null);

        verify(bitcoinCore).ensureWalletLoaded("kerosene");
        verify(bitcoinCore).ensureWalletLoaded("kerosene-funds");
        verify(bitcoinCore).ensureWalletLoaded("kerosene-profit");
    }

    @Test
    void rejectsMismatchedBitcoinCoreNetwork() {
        KfeSystemWalletService systemWalletService = mock(KfeSystemWalletService.class);
        BitcoinCoreRpcClient bitcoinCore = mock(BitcoinCoreRpcClient.class);
        when(systemWalletService.ensureSystemWallets()).thenReturn(wallets());
        when(bitcoinCore.chain()).thenReturn("main");

        KfeBitcoinRuntimeBootstrap bootstrap = new KfeBitcoinRuntimeBootstrap(
                systemWalletService,
                provider(bitcoinCore),
                true,
                true,
                true,
                false,
                false,
                "testnet",
                "kerosene",
                "kerosene-funds",
                "kerosene-profit");

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bitcoin Core chain mismatch");
    }

    @Test
    void rejectsUnsyncedBitcoinCoreWhenSyncIsRequired() {
        KfeSystemWalletService systemWalletService = mock(KfeSystemWalletService.class);
        BitcoinCoreRpcClient bitcoinCore = mock(BitcoinCoreRpcClient.class);
        when(systemWalletService.ensureSystemWallets()).thenReturn(wallets());
        when(bitcoinCore.chain()).thenReturn("testnet4");
        when(bitcoinCore.blockchainInfo()).thenReturn(blockchainInfo(true));

        KfeBitcoinRuntimeBootstrap bootstrap = new KfeBitcoinRuntimeBootstrap(
                systemWalletService,
                provider(bitcoinCore),
                true,
                true,
                true,
                true,
                false,
                "testnet4",
                "kerosene",
                "kerosene-funds",
                "kerosene-profit");

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initial block download");
    }

    private KfeSystemWalletService.SystemWallets wallets() {
        return new KfeSystemWalletService.SystemWallets(UUID.randomUUID(), UUID.randomUUID());
    }

    private JsonNode blockchainInfo(boolean initialBlockDownload) {
        return OBJECT_MAPPER.createObjectNode()
                .put("chain", "testnet4")
                .put("blocks", 56_000L)
                .put("headers", 142_000L)
                .put("verificationprogress", 0.64D)
                .put("initialblockdownload", initialBlockDownload);
    }

    private ObjectProvider<BitcoinCoreRpcClient> provider(BitcoinCoreRpcClient bitcoinCore) {
        return new ObjectProvider<>() {
            @Override
            public BitcoinCoreRpcClient getObject(Object... args) {
                return bitcoinCore;
            }

            @Override
            public BitcoinCoreRpcClient getIfAvailable() {
                return bitcoinCore;
            }

            @Override
            public BitcoinCoreRpcClient getIfUnique() {
                return bitcoinCore;
            }

            @Override
            public BitcoinCoreRpcClient getObject() {
                return bitcoinCore;
            }
        };
    }
}
