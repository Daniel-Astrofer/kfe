package source.kfe.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import source.common.exception.ErrorCodes;
import source.common.exception.StructuredPlatformException;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.HttpStatus;

class KfeRemoteFinancialTransactionApprovalClientTest {

    @Test
    void postsLocalFactorApprovalToAuthServer() throws Exception {
        KfeRemoteFinancialTransactionApprovalClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://server.test/internal/kfe/transaction-approval/local-factor"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"userId\":42,\"deviceRef\":\"device\",\"factor\":\"1234\"}"))
                .andRespond(withSuccess());

        client.approveLocalFactor(42L, "device", "1234");

        server.verify();
    }

    @Test
    void postsWalletOutboundApprovalToAuthServer() throws Exception {
        KfeRemoteFinancialTransactionApprovalClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://server.test/internal/kfe/transaction-approval/wallet-outbound"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "actorUserId":41,
                          "ownerUserId":42,
                          "factorA":"totp",
                          "factorB":"assertion",
                          "factorC":"phrase"
                        }
                        """))
                .andRespond(withSuccess());

        client.approveWalletOutbound(41L, 42L, "totp", "assertion", "phrase");

        server.verify();
    }

    @Test
    void rejectsMissingInternalCredentialBeforeCallingAuthServer() {
        KfeRemoteFinancialTransactionApprovalClient client = client("");

        assertThrows(IllegalStateException.class, () -> client.approveLocalFactor(42L, "device", "1234"));
    }

    @Test
    void mapsRemoteAuthChallengeToStructuredPlatformException() throws Exception {
        KfeRemoteFinancialTransactionApprovalClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("http://server.test/internal/kfe/transaction-approval/custody-transfer"))
                .andRespond(withStatus(HttpStatus.PRECONDITION_REQUIRED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "success": false,
                                  "message": "PASSKEY_CHALLENGE_REQUIRED:abcdef",
                                  "errorCode": "AUTH_012",
                                  "data": {"challenge": "abcdef"}
                                }
                                """));

        StructuredPlatformException exception = assertThrows(
                StructuredPlatformException.class,
                () -> client.approveCustodyTransfer(42L, null));

        assertEquals(HttpStatus.PRECONDITION_REQUIRED, exception.getStatus());
        assertEquals(ErrorCodes.AUTH_PASSKEY_CHALLENGE, exception.getErrorCode());
        assertEquals("PASSKEY_CHALLENGE_REQUIRED:abcdef", exception.getMessage());
        server.verify();
    }

    private KfeRemoteFinancialTransactionApprovalClient client(String credential) {
        return new KfeRemoteFinancialTransactionApprovalClient(
                new RestTemplateBuilder(),
                new ObjectMapper(),
                "http://server.test",
                credential,
                100,
                100);
    }

    private RestTemplate restTemplate(KfeRemoteFinancialTransactionApprovalClient client) throws Exception {
        Field field = KfeRemoteFinancialTransactionApprovalClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return (RestTemplate) field.get(client);
    }
}
