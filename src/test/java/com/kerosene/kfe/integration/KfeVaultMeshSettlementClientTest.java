package com.kerosene.kfe.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import com.kerosene.common.vaultmesh.VaultMeshDayAdvanceResult;
import com.kerosene.common.vaultmesh.VaultMeshDayStatus;
import com.kerosene.common.vaultmesh.VaultMeshIntent;
import com.kerosene.common.vaultmesh.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshReshareResult;

import javax.net.ssl.SSLContext;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KfeVaultMeshSettlementClientTest {

    @TempDir
    Path tempDir;

    @Test
    void submitIntentPostsSignPathAndMapsAcceptedReceipt() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        VaultMeshIntent intent = new VaultMeshIntent(
                "intent-1", "USERS", "bc1qtest", 12_345L, "policy-a", Instant.ofEpochMilli(1_700_000_000_000L),
                null, null, null, null);
        String hash = KfeVaultMeshSettlementClient.messageHash(intent);

        server.expect(requestTo("http://vault.test:7701/sign/intent-1/" + hash))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Vault-Token", "test-vault-token"))
                .andRespond(withSuccess(
                        """
                        {"session_id":"intent-1","message_hash":"%s","value":42,"scheme":"lab-shamir-threshold-v1"}
                        """.formatted(hash),
                        MediaType.APPLICATION_JSON));

        VaultMeshReceipt receipt = client.submitIntent(intent);

        assertThat(receipt.status()).isEqualTo(VaultMeshReceipt.Status.ACCEPTED);
        assertThat(receipt.intentId()).isEqualTo("intent-1");
        assertThat(receipt.txidOrProof()).isEqualTo("42");
        server.verify();
    }

    @Test
    void submitIntentMapsFailStopFromVaultError() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        VaultMeshIntent intent = new VaultMeshIntent(
                "intent-2", "USERS", "bc1q", 1L, "p", Instant.ofEpochMilli(1L),
                null, null, null, null);
        String hash = KfeVaultMeshSettlementClient.messageHash(intent);

        server.expect(requestTo("http://vault.test:7701/sign/intent-2/" + hash))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"fail-stop: online < t\"}"));

        VaultMeshReceipt receipt = client.submitIntent(intent);

        assertThat(receipt.status()).isEqualTo(VaultMeshReceipt.Status.FAIL_STOP);
        assertThat(receipt.reasonCode()).contains("fail-stop");
        server.verify();
    }

    @Test
    void submitIntentRejectsBlankIntentId() {
        assertThatThrownBy(() ->
                new VaultMeshIntent(" ", "USERS", "x", 1L, "p", Instant.ofEpochMilli(1L),
                        null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intentId required");
    }

    @Test
    void signPsbtRejectsInfraBucketOnTaproot() {
        KfeVaultMeshSettlementClient client = client();
        var receipt = client.signPsbt(new com.kerosene.common.vaultmesh.VaultMeshPsbtRequest(
                "intent-infra",
                "sess-infra",
                "INFRA",
                "tb1q-infra-ops",
                1_000L,
                "cHNidP8BAHic"));
        assertThat(receipt.status()).isEqualTo(VaultMeshReceipt.Status.REJECTED);
        assertThat(receipt.reasonCode()).isEqualTo("MESH_BUCKET_NOT_SHARED_TAPROOT:INFRA");
    }

    @Test
    void getUsersDepositAddressRejectsNonTb1p() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));

        server.expect(requestTo("http://vault.test:7701/v1/bitcoin/deposit?bucket=USERS"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {"network":"testnet3","address":"tb1qnotp","descriptor":"tr(x)","scheme":"frost-secp256k1-tr-v3","output_pubkey":"aa","xonly_pubkey":"bb"}
                        """,
                        MediaType.APPLICATION_JSON));

        VaultMeshDepositInfo info = client.getUsersDepositAddress();
        assertThat(info).isNull();
        server.verify();
    }

    @Test
    void getDayStatusMapsCurrentEpoch() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://vault.test:7701/v1/day/current"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Vault-Token", "test-vault-token"))
                .andRespond(withSuccess(
                        "{\"day_epoch\":\"2099-01-01\"}", MediaType.APPLICATION_JSON));

        VaultMeshDayStatus status = client.getDayStatus();

        assertThat(status.upToDate()).isTrue();
        assertThat(status.dayEpoch()).isEqualTo("2099-01-01");
        server.verify();
    }

    @Test
    void getDayStatusMapsStaleConflict() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://vault.test:7701/v1/day/current"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"day_epoch stale: have 2026-07-21, need 2026-07-22\"}"));

        VaultMeshDayStatus status = client.getDayStatus();

        assertThat(status.stale()).isTrue();
        assertThat(status.upToDate()).isFalse();
        assertThat(status.dayEpoch()).isEqualTo("2026-07-21");
        assertThat(status.neededDayEpoch()).isEqualTo("2026-07-22");
        server.verify();
    }

    @Test
    void voteAdvanceAndReshareHitVaultEndpoints() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));

        server.expect(requestTo("http://vault.test:7701/v1/day/vote"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Vault-Token", "test-vault-token"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://vault.test:7701/v1/day/advance"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"day_epoch\":\"2026-07-22\",\"advanced\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://vault.test:7701/v1/reshare/trigger"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"reshared\":true,\"policy\":\"daily\",\"reason\":\"kfe-day-rotation\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.voteDay("kfe", "2026-07-22").ok()).isTrue();
        VaultMeshDayAdvanceResult advanced = client.advanceDay();
        assertThat(advanced.ok()).isTrue();
        assertThat(advanced.dayEpoch()).isEqualTo("2026-07-22");
        assertThat(advanced.advanced()).isTrue();
        VaultMeshReshareResult reshare = client.triggerReshare("kfe-day-rotation");
        assertThat(reshare.ok()).isTrue();
        assertThat(reshare.policy()).isEqualTo("daily");
        server.verify();
    }

    @Test
    void tlsEnabledOmitsVaultTokenHeader() throws Exception {
        Path certs = materializeLabCerts();
        KfeVaultMeshSettlementClient client = new KfeVaultMeshSettlementClient(
                new RestTemplateBuilder(),
                new ObjectMapper(),
                "https://vault.test:7701",
                1000L,
                1000L,
                "must-not-send",
                true,
                certs.resolve("vault-client.crt").toString(),
                certs.resolve("vault-client.pkcs8.key").toString(),
                certs.resolve("ca.crt").toString(),
                "",
                "",
                "PKCS12",
                "",
                "",
                "PKCS12",
                true,
                "",
                3,
                2,
                "https://vault.test:7701",
                "direct",
                "",
                9050);
        assertThat(client.tlsEnabled()).isTrue();

        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        VaultMeshIntent intent = new VaultMeshIntent("intent-tls", "USERS", "bc1q", 1L, "p", Instant.ofEpochMilli(1L),
                null, null, null, null);
        String hash = KfeVaultMeshSettlementClient.messageHash(intent);
        server.expect(requestTo("https://vault.test:7701/sign/intent-tls/" + hash))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(request.getHeaders().get("X-Vault-Token")).isNull())
                .andRespond(withSuccess(
                        "{\"session_id\":\"intent-tls\",\"value\":1}", MediaType.APPLICATION_JSON));

        assertThat(client.submitIntent(intent).status()).isEqualTo(VaultMeshReceipt.Status.ACCEPTED);
        server.verify();
    }

    @Test
    void tlsSupportBuildsContextFromPemAndPkcs12() throws Exception {
        Path certs = materializeLabCerts();
        SSLContext pem = KfeVaultMeshTlsSupport.buildSslContext(
                certs.resolve("vault-client.crt").toString(),
                certs.resolve("vault-client.pkcs8.key").toString(),
                certs.resolve("ca.crt").toString(),
                "",
                "",
                "PKCS12",
                "",
                "",
                "PKCS12");
        assertThat(pem).isNotNull();

        SSLContext p12 = KfeVaultMeshTlsSupport.buildSslContext(
                "",
                "",
                "",
                certs.resolve("kfe-client.p12").toString(),
                "changeit",
                "PKCS12",
                certs.resolve("truststore.p12").toString(),
                "changeit",
                "PKCS12");
        assertThat(p12).isNotNull();

        X509Certificate leaf = KfeVaultMeshTlsSupport.readFirstCert(certs.resolve("vault-client.crt"));
        assertThat(leaf.getSubjectX500Principal().getName()).contains("kerosene-kfe-lab");
    }

    @Test
    void tlsEnabledWithoutMaterialsFailsFast() {
        assertThatThrownBy(() -> new KfeVaultMeshSettlementClient(
                        new RestTemplateBuilder(),
                        new ObjectMapper(),
                        "https://vault.test:7701",
                        1000L,
                        1000L,
                        "",
                        true,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "PKCS12",
                        "",
                        "",
                        "PKCS12",
                        true,
                        "",
                        3,
                        2,
                        "https://vault.test:7701",
                        "direct",
                        "",
                        9050))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kfe.vaultmesh.tls.enabled=true");
    }

    @Test
    void torTransportAcceptsOnlyMtlsOnionUrlsAndUsesUnresolvedSocksAddress() {
        java.net.Proxy proxy = KfeVaultMeshTlsSupport.validateTransport(
                "tor", "tor-proxy", 9050, true, List.of("https://vaultabcdefghijkl.onion:7801"));

        assertThat(proxy.type()).isEqualTo(java.net.Proxy.Type.SOCKS);
        assertThat((java.net.InetSocketAddress) proxy.address())
                .satisfies(address -> {
                    assertThat(address.isUnresolved()).isTrue();
                    assertThat(address.getHostString()).isEqualTo("tor-proxy");
                    assertThat(address.getPort()).isEqualTo(9050);
                });
    }

    @Test
    void torTransportFailsClosedForClearnetOrMissingMtls() {
        assertThatThrownBy(() -> KfeVaultMeshTlsSupport.validateTransport(
                        "tor", "tor-proxy", 9050, true, List.of("https://vault-1:7801")))
                .hasMessageContaining("https://*.onion");
        assertThatThrownBy(() -> KfeVaultMeshTlsSupport.validateTransport(
                        "tor", "tor-proxy", 9050, false, List.of("https://vaultabcdefghijkl.onion")))
                .hasMessageContaining("requires mTLS");
    }

    private Path materializeLabCerts() throws Exception {
        Path out = tempDir.resolve("mtls-certs");
        Files.createDirectories(out);
        Path script = locateGenScript();
        ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
        pb.environment().put("VAULT_LAB_MTLS_OUT", out.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        assertThat(finished).as("cert gen timed out").isTrue();
        assertThat(process.exitValue())
                .as("cert gen failed: %s", output)
                .isZero();
        assertThat(out.resolve("vault-client.pkcs8.key")).exists();
        return out;
    }

    private static Path locateGenScript() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path[] candidates = new Path[] {
            cwd.resolve("src/test/resources/tls/gen_lab_mtls_certs.sh"),
            cwd.resolve("scripts/vault/gen_lab_mtls_certs.sh"),
            cwd.resolve("../../scripts/vault/gen_lab_mtls_certs.sh"),
            cwd.resolve("../../../scripts/vault/gen_lab_mtls_certs.sh"),
            cwd.resolve("backend/kerosene-vault/scripts/gen_lab_mtls_certs.sh"),
            cwd.resolve("../kerosene-vault/scripts/gen_lab_mtls_certs.sh"),
            cwd.resolve("../../kerosene-vault/scripts/gen_lab_mtls_certs.sh"),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("gen_lab_mtls_certs.sh not found from cwd=" + cwd);
    }

    private static KfeVaultMeshSettlementClient client() {
        return new KfeVaultMeshSettlementClient(
                new RestTemplateBuilder(),
                new ObjectMapper(),
                "http://vault.test:7701",
                1000L,
                1000L,
                "test-vault-token");
    }

    private static RestTemplate restTemplate(KfeVaultMeshSettlementClient client) throws Exception {
        Field field = KfeVaultMeshSettlementClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return (RestTemplate) field.get(client);
    }
}
