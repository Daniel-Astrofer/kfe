package source.kfe.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KfeRemoteFinancialNotificationClientTest {

    @Test
    void postsDepositConfirmedNotificationToAuthServer() throws Exception {
        KfeRemoteFinancialNotificationClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        server.expect(requestTo("http://server.test/internal/kfe/notifications/deposit-confirmed"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "userId":42,
                          "transactionId":"%s",
                          "walletId":"%s",
                          "rail":"ONCHAIN",
                          "creditedSats":1500,
                          "confirmations":3
                        }
                        """.formatted(transactionId, walletId)))
                .andRespond(withSuccess());

        client.notifyDepositConfirmed(42L, transactionId, walletId, "ONCHAIN", 1500L, 3);

        server.verify();
    }

    @Test
    void postsPaymentRequestDepositConfirmedNotificationToAuthServer() throws Exception {
        KfeRemoteFinancialNotificationClient client = client("credential");
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        UUID transactionId = UUID.randomUUID();
        UUID paymentRequestId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        server.expect(requestTo("http://server.test/internal/kfe/notifications/payment-request-deposit-confirmed"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-KFE-Internal-Secret", "credential"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "userId":42,
                          "transactionId":"%s",
                          "paymentRequestId":"%s",
                          "publicId":"public-id",
                          "walletId":"%s",
                          "rail":"LIGHTNING",
                          "creditedSats":2500
                        }
                        """.formatted(transactionId, paymentRequestId, walletId)))
                .andRespond(withSuccess());

        client.notifyPaymentRequestDepositConfirmed(
                42L,
                transactionId,
                paymentRequestId,
                "public-id",
                walletId,
                "LIGHTNING",
                2500L);

        server.verify();
    }

    @Test
    void rejectsMissingInternalCredentialBeforeCallingAuthServer() {
        KfeRemoteFinancialNotificationClient client = client("");

        assertThrows(
                IllegalStateException.class,
                () -> client.notifyDepositConfirmed(
                        42L,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ONCHAIN",
                        1500L,
                        3));
    }

    private KfeRemoteFinancialNotificationClient client(String credential) {
        return new KfeRemoteFinancialNotificationClient(
                new RestTemplateBuilder(),
                "http://server.test",
                credential,
                100,
                100);
    }

    private RestTemplate restTemplate(KfeRemoteFinancialNotificationClient client) throws Exception {
        Field field = KfeRemoteFinancialNotificationClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return (RestTemplate) field.get(client);
    }
}
