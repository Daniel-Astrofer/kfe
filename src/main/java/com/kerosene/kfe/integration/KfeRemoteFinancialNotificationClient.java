package com.kerosene.kfe.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import source.common.financial.FinancialOutboundNotificationRequest;
import source.common.financial.FinancialPaymentRequestDepositConfirmedNotificationRequest;

import java.time.Duration;
import java.util.UUID;

@Component
@Profile("kfe")
@ConditionalOnProperty(name = "kfe.remote.notifications.enabled", havingValue = "true", matchIfMissing = true)
public class KfeRemoteFinancialNotificationClient implements FinancialNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(KfeRemoteFinancialNotificationClient.class);
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

    @Override
    public void notifyDepositDetected(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long creditedSats,
            int confirmations) {
        post("/internal/kfe/notifications/deposit-detected",
                new FinancialDepositConfirmedNotificationRequest(
                        userId,
                        transactionId,
                        walletId,
                        rail,
                        creditedSats,
                        confirmations));
    }

    @Override
    public void notifyDepositConfirmationProgress(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long creditedSats,
            int confirmations) {
        post("/internal/kfe/notifications/deposit-progress",
                new FinancialDepositConfirmedNotificationRequest(
                        userId,
                        transactionId,
                        walletId,
                        rail,
                        creditedSats,
                        confirmations));
    }

    @Override
    public void notifyOutboundDetected(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long amountSats,
            int confirmations,
            String destinationHint) {
        post("/internal/kfe/notifications/outbound-detected",
                new FinancialOutboundNotificationRequest(
                        userId,
                        transactionId,
                        walletId,
                        rail,
                        amountSats,
                        confirmations,
                        destinationHint));
    }

    @Override
    public void notifyOutboundConfirmed(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long amountSats,
            int confirmations) {
        post("/internal/kfe/notifications/outbound-confirmed",
                new FinancialOutboundNotificationRequest(
                        userId,
                        transactionId,
                        walletId,
                        rail,
                        amountSats,
                        confirmations,
                        null));
    }

    @Override
    public void notifyInternalTransferReceived(
            Long receiverUserId,
            UUID transactionId,
            UUID walletId,
            long amountSats) {
        post("/internal/kfe/notifications/internal-transfer-received",
                new source.common.financial.FinancialInternalTransferNotificationRequest(
                        receiverUserId,
                        transactionId,
                        walletId,
                        amountSats));
    }

    @Override
    public void notifyInternalTransferSent(
            Long senderUserId,
            UUID transactionId,
            UUID walletId,
            long amountSats) {
        post("/internal/kfe/notifications/internal-transfer-sent",
                new source.common.financial.FinancialInternalTransferNotificationRequest(
                        senderUserId,
                        transactionId,
                        walletId,
                        amountSats));
    }

    @Override
    public void notifyExternalPaymentSent(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long amountSats) {
        post("/internal/kfe/notifications/external-payment-sent",
                new source.common.financial.FinancialExternalPaymentNotificationRequest(
                        userId,
                        transactionId,
                        walletId,
                        rail,
                        amountSats));
    }

    /**
     * Best-effort push to the auth/server notification API.
     *
     * <p>Must never abort ledger settlement: a missing route (404 on older server images),
     * auth glitch, or down server is not a payment failure. Mobile was seeing
     * "operation rejected" because submit rolled back when this threw.
     */
    private void post(String path, Object request) {
        // Missing secret is a deploy misconfiguration — still fail fast so ops notice.
        HttpEntity<Object> entity = internalJsonEntity(request);
        try {
            restTemplate.postForEntity(baseUrl + path, entity, Void.class);
        } catch (RestClientResponseException exception) {
            log.warn(
                    "[KFE Notify] auth server rejected {} with HTTP {} — continuing without push",
                    path,
                    exception.getStatusCode().value());
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Notify] failed to POST {} ({}): {} — continuing without push",
                    path,
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
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
