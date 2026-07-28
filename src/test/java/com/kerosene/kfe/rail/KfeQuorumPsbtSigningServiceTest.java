package com.kerosene.kfe.rail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kerosene.common.vaultmesh.VaultMeshPsbtReceipt;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;

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
            null,   // vaultMeshSettlementPort
            false,  // meshOnly
            "USERS", // meshBucket
            1,
            6,
            "http://signer-one",
            "api-key",
            "signer-one",
            true,
            false,
            "bitcoin-core-wallet",
            "");     // signerTlsFingerprints

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
                null,   // vaultMeshSettlementPort
                false,  // meshOnly
                "USERS", // meshBucket
                1,
                6,
                "",
                "",
                "",
                false,
                true,
                "bitcoin-core-wallet",
                "");     // signerTlsFingerprints

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
        // PSBT integrity check mocks
        JsonNode decodedTx = decodedPsbtNode("bcrt1qdestination", 100_000L);
        when(bitcoinCore.decodePsbt("funded-psbt")).thenReturn(decodedTx);
        when(bitcoinCore.decodePsbt("signed-psbt")).thenReturn(decodedTx);
        when(bitcoinCore.decodePsbt("combined-psbt")).thenReturn(decodedTx);
        when(bitcoinCore.decodeRawTransaction("deadbeef")).thenReturn(decodedTx.get("tx"));
        when(bitcoinCore.testMempoolAccept("deadbeef")).thenReturn(mempoolAccept(true));

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
                null,   // vaultMeshSettlementPort
                false,  // meshOnly
                "USERS", // meshBucket
                1,
                6,
                "",
                "",
                "",
                true,
                false,
                "bitcoin-core-wallet",
                "");     // signerTlsFingerprints

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
                        intent.intentId(), VaultMeshReceipt.Status.REJECTED, "UNUSED", null, Instant.ofEpochMilli(1L));
            }

            @Override
            public VaultMeshPsbtReceipt signPsbt(com.kerosene.common.vaultmesh.VaultMeshPsbtRequest request) {
                return new VaultMeshPsbtReceipt(
                        request.intentId(),
                        VaultMeshReceipt.Status.ACCEPTED,
                        null,
                        "mesh-signed-psbt",
                        "sig-proof",
                        Instant.ofEpochMilli(1L));
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
                "bitcoin-core-wallet",
                "");     // signerTlsFingerprints

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
        // PSBT integrity check mocks
        JsonNode decodedTx = decodedPsbtNode("tb1qdestination", 50_000L);
        when(bitcoinCore.decodePsbt("funded-psbt")).thenReturn(decodedTx);
        when(bitcoinCore.decodePsbt("mesh-signed-psbt")).thenReturn(decodedTx);
        when(bitcoinCore.decodeRawTransaction("cafebabe")).thenReturn(decodedTx.get("tx"));
        when(bitcoinCore.testMempoolAccept("cafebabe")).thenReturn(mempoolAccept(true));

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

    /**
     * Creates a minimal valid-looking decoded PSBT JSON node with the expected
     * destination and amount for test assertions.
     */
    static JsonNode decodedPsbtNode(String destinationAddress, long amountSats) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode tx = mapper.createObjectNode();
        tx.put("version", 2);
        tx.put("locktime", 0);

        ArrayNode vin = mapper.createArrayNode();
        ObjectNode input = mapper.createObjectNode();
        input.put("txid", "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        input.put("vout", 0);
        input.put("sequence", 4294967293L);
        vin.add(input);
        tx.set("vin", vin);

        ArrayNode vout = mapper.createArrayNode();
        ObjectNode output = mapper.createObjectNode();
        output.put("value", BigDecimal.valueOf(amountSats).divide(BigDecimal.valueOf(100_000_000)));
        output.put("n", 0);
        ObjectNode spk = mapper.createObjectNode();
        spk.put("address", destinationAddress);
        output.set("scriptPubKey", spk);
        vout.add(output);
        tx.set("vout", vout);

        root.set("tx", tx);

        // Add minimal witness_utxo for fee computation
        ArrayNode inputs = mapper.createArrayNode();
        ObjectNode psbtInput = mapper.createObjectNode();
        ObjectNode witnessUtxo = mapper.createObjectNode();
        // Fee = 200 sats for simplicity; input amount = output amount + fee
        witnessUtxo.put("amount",
                BigDecimal.valueOf(amountSats + 200L).divide(BigDecimal.valueOf(100_000_000)));
        psbtInput.set("witness_utxo", witnessUtxo);
        inputs.add(psbtInput);
        root.set("inputs", inputs);

        return root;
    }

    static JsonNode mempoolAccept(boolean allowed) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode arr = mapper.createArrayNode();
        ObjectNode entry = mapper.createObjectNode();
        entry.put("txid", "0000000000000000000000000000000000000000000000000000000000000000");
        entry.put("allowed", allowed);
        if (!allowed) {
            entry.put("reject-reason", "test-reject");
        }
        arr.add(entry);
        return arr;
    }
}
