package source.kfe.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import com.kerosene.common.vaultmesh.VaultMeshIntent;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KfeVaultMeshSettlementClientTest {

    @Test
    void submitIntentPostsSignPathAndMapsAcceptedReceipt() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        VaultMeshIntent intent = new VaultMeshIntent(
                "intent-1", "USERS", "bc1qtest", 12_345L, "policy-a", 1_700_000_000_000L);
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
                "intent-2", "USERS", "bc1q", 1L, "p", 1L);
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
        KfeVaultMeshSettlementClient client = client();
        VaultMeshReceipt receipt = client.submitIntent(
                new VaultMeshIntent(" ", "USERS", "x", 1L, "p", 1L));
        assertThat(receipt.status()).isEqualTo(VaultMeshReceipt.Status.REJECTED);
        assertThat(receipt.reasonCode()).isEqualTo("INVALID_INTENT");
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
