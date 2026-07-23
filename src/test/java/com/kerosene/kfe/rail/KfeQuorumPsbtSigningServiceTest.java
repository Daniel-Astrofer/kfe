package com.kerosene.kfe.rail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.vaultmesh.VaultMeshPsbtReceipt;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;
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
            true,
            false,
            "bitcoin-core-wallet");

    @Test
    void rejectsFundedPsbtBeforeSigningWhenActualFeeExceedsReservedLimit() {
        when(bitcoinCore.createFundedPsbt("bcrt1qdestination", 100_000L, 6, null, "bech32"))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("funded-psbt", 500L));

        assertThatThrownBy(() -> service.preflight(command(499L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Funded PSBT fee exceeds configured on-chain fee cap.")
                .hasMessageContaining("actualFeeSats=500")
                .hasMessageContaining("capFeeSats=499");
    }

    @Test
    void acceptsFundedPsbtWhenActualFeeFitsReservedLimit() {
        when(bitcoinCore.createFundedPsbt("bcrt1qdestination", 100_000L, 6, null, "bech32"))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("funded-psbt", 500L));

        var preflight = service.preflight(command(500L));

        assertThat(preflight.feeSats()).isEqualTo(500L);
        assertThat(preflight.configuredSignerCount()).isEqualTo(1);
    }

    @Test
    void localCoreSignerAloneSatisfiesQuorumWhenEnabled() {
        KfeQuorumPsbtSigningService localOnly = new KfeQuorumPsbtSigningService(
                bitcoinCoreProvider,
                mock(RestTemplate.class),
                new ObjectMapper(),
                1,
                6,
                "",
                "",
                "",
                false,
                true,
                "bitcoin-core-wallet");

        when(bitcoinCore.createFundedPsbt(
                        org.mockito.ArgumentMatchers.eq("bcrt1qdestination"),
                        org.mockito.ArgumentMatchers.eq(100_000L),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("bech32")))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("funded-psbt", 200L));
        when(bitcoinCore.walletProcessPsbt("funded-psbt")).thenReturn("signed-psbt");
        when(bitcoinCore.combinePsbt(org.mockito.ArgumentMatchers.anyList())).thenReturn("combined-psbt");
        when(bitcoinCore.finalizePsbt("combined-psbt"))
                .thenReturn(new BitcoinCoreRpcClient.FinalizedPsbt("deadbeef", true));
        when(bitcoinCore.sendRawTransaction("deadbeef")).thenReturn("txid-local");

        var execution = localOnly.execute(new KfeOnchainPaymentGateway.OnchainPaymentCommand(
                42L,
                null,
                "wallet",
                "bcrt1qdestination",
                100_000L,
                500L,
                "memo",
                "idem",
                "proof"));

        assertThat(execution.txid()).isEqualTo("txid-local");
        assertThat(execution.acceptedSigners()).containsExactly("bitcoin-core-wallet");
    }

    @Test
    void rejectsWhenNoSignerSeatsConfigured() {
        KfeQuorumPsbtSigningService none = new KfeQuorumPsbtSigningService(
                bitcoinCoreProvider,
                mock(RestTemplate.class),
                new ObjectMapper(),
                1,
                6,
                "",
                "",
                "",
                true,
                false,
                "bitcoin-core-wallet");

        assertThatThrownBy(() -> none.preflight(command(500L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No quorum PSBT signer");
    }

    @Test
    void meshOnlyUsesVaultMeshSignerAndSkipsLocalCore() {
        VaultMeshSettlementPort mesh = new VaultMeshSettlementPort() {
            @Override
            public VaultMeshReceipt submitIntent(com.kerosene.common.vaultmesh.VaultMeshIntent intent) {
                return new VaultMeshReceipt(
                        intent.intentId(), VaultMeshReceipt.Status.REJECTED, "UNUSED", null, 1L);
            }

            @Override
            public VaultMeshPsbtReceipt signPsbt(com.kerosene.common.vaultmesh.VaultMeshPsbtRequest request) {
                return new VaultMeshPsbtReceipt(
                        request.intentId(),
                        VaultMeshReceipt.Status.ACCEPTED,
                        null,
                        "mesh-signed-psbt",
                        "sig-proof",
                        1L);
            }
        };
        KfeQuorumPsbtSigningService meshOnly = new KfeQuorumPsbtSigningService(
                bitcoinCoreProvider,
                mock(RestTemplate.class),
                new ObjectMapper(),
                mesh,
                true,
                "USERS",
                2,
                6,
                "",
                "",
                "",
                false,
                true,
                "bitcoin-core-wallet");

        when(bitcoinCore.createFundedPsbt(
                        org.mockito.ArgumentMatchers.eq("tb1qdestination"),
                        org.mockito.ArgumentMatchers.eq(50_000L),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("bech32m")))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("funded-psbt", 150L));
        when(bitcoinCore.finalizePsbt("mesh-signed-psbt"))
                .thenReturn(new BitcoinCoreRpcClient.FinalizedPsbt("cafebabe", true));
        when(bitcoinCore.sendRawTransaction("cafebabe")).thenReturn("txid-mesh");

        var execution = meshOnly.execute(new KfeOnchainPaymentGateway.OnchainPaymentCommand(
                7L,
                null,
                "wallet",
                "tb1qdestination",
                50_000L,
                500L,
                "memo",
                "idem-mesh",
                "proof"));

        assertThat(execution.txid()).isEqualTo("txid-mesh");
        assertThat(execution.acceptedSigners()).containsExactly("vault-mesh");
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
