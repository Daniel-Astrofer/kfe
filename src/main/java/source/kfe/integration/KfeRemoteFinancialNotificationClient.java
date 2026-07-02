package source.kfe.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import source.common.financial.FinancialDepositConfirmedNotificationRequest;
import source.common.financial.FinancialNotificationPort;
import source.common.financial.FinancialPaymentRequestDepositConfirmedNotificationRequest;

import java.time.Duration;
import java.util.UUID;

@Component
@Profile("kfe")
@ConditionalOnProperty(name = "kfe.remote.notifications.enabled", havingValue = "true", matchIfMissing = true)
public class KfeRemoteFinancialNotificationClient implements FinancialNotificationPort {

    private static final String INTERNAL_HEADER = "X-KFE-Internal-Secret";
    private static final String DEFAULT_BASE_URL = "http://server:8080";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalSecret;

    public KfeRemoteFinancialNotificationClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${auth.remote.base-url:http://server:8080}") String baseUrl,
            @Value("${kfe.internal.shared-secret:}") String internalSecret,
            @Value("${auth.remote.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${auth.remote.read-timeout-ms:5000}") long readTimeoutMs) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.internalSecret = internalSecret;
    }

    @Override
    public void notifyDepositConfirmed(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long creditedSats,
            int confirmations) {
        post("/internal/kfe/notifications/deposit-confirmed",
                new FinancialDepositConfirmedNotificationRequest(
                        userId,
                        transactionId,
                        walletId,
                        rail,
                        creditedSats,
                        confirmations));
    }

    @Override
    public void notifyPaymentRequestDepositConfirmed(
            Long userId,
            UUID transactionId,
            UUID paymentRequestId,
            String publicId,
            UUID walletId,
            String rail,
            long creditedSats) {
        post("/internal/kfe/notifications/payment-request-deposit-confirmed",
                new FinancialPaymentRequestDepositConfirmedNotificationRequest(
                        userId,
                        transactionId,
                        paymentRequestId,
                        publicId,
                        walletId,
                        rail,
                        creditedSats));
    }

    private void post(String path, Object request) {
        try {
            restTemplate.postForEntity(baseUrl + path, internalJsonEntity(request), Void.class);
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException(
                    "KFE financial notification was rejected by auth server: "
                            + exception.getStatusCode().value(),
                    exception);
        }
    }

    private <T> HttpEntity<T> internalJsonEntity(T body) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new IllegalStateException("kfe.internal.shared-secret must be configured for KFE to Auth calls");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(INTERNAL_HEADER, internalSecret);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
