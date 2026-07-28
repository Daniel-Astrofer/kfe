package com.kerosene.kfe.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.common.financial.FinancialColdWalletPsbtApprovalRequest;
import com.kerosene.common.financial.FinancialCustodyTransferApprovalRequest;
import com.kerosene.common.financial.FinancialLocalFactorApprovalRequest;
import com.kerosene.common.financial.FinancialTransactionApprovalPort;
import com.kerosene.common.financial.FinancialWalletOutboundApprovalRequest;
import com.kerosene.common.financial.DeviceProof;
import com.kerosene.common.financial.PasskeyAssertion;
import com.kerosene.common.financial.RecoveryApproval;

import java.time.Duration;
import java.util.Map;

@Component
@Profile("kfe")
@ConditionalOnProperty(name = "kfe.remote.transaction-approval.enabled", havingValue = "true", matchIfMissing = true)
public class KfeRemoteFinancialTransactionApprovalClient implements FinancialTransactionApprovalPort {

    private static final String INTERNAL_HEADER = "X-KFE-Internal-Secret";
    private static final String DEFAULT_BASE_URL = "http://server:8080";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String internalSecret;

    public KfeRemoteFinancialTransactionApprovalClient(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            @Value("${auth.remote.base-url:http://server:8080}") String baseUrl,
            @Value("${kfe.internal.shared-secret:}") String internalSecret,
            @Value("${auth.remote.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${auth.remote.read-timeout-ms:5000}") long readTimeoutMs) {
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.internalSecret = internalSecret;
    }

    @Override
    public void approveLocalFactor(Long userId, String deviceRef, String factor) {
        throw new UnsupportedOperationException(
                "Legacy string approval cannot be converted into a device-bound proof");
    }

    @Override
    public void approveCustodyTransfer(Long userId, String assertion) {
        throw new UnsupportedOperationException(
                "Legacy custody assertion cannot be converted into a WebAuthn assertion");
    }

    @Override
    public void approveWalletOutbound(
            Long actorUserId,
            Long ownerUserId,
            String factorA,
            String factorB,
            String factorC) {
        throw new UnsupportedOperationException(
                "Legacy wallet factors cannot be converted into typed approval proofs");
    }

    @Override
    public void approveColdWalletPsbt(Long userId, String factor) {
        throw new UnsupportedOperationException(
                "Legacy cold-wallet factor cannot be converted into a device-bound proof");
    }

    @Override
    public void approveLocalFactor(Long userId, String deviceRef, DeviceProof factor) {
        post("/internal/kfe/transaction-approval/local-factor",
                new FinancialLocalFactorApprovalRequest(userId, deviceRef, factor));
    }

    @Override
    public void approveCustodyTransfer(Long userId, PasskeyAssertion assertion) {
        post("/internal/kfe/transaction-approval/custody-transfer",
                new FinancialCustodyTransferApprovalRequest(userId, assertion));
    }

    @Override
    public void approveWalletOutbound(
            Long actorUserId,
            Long ownerUserId,
            PasskeyAssertion passkeyAssertion,
            RecoveryApproval recoveryApproval,
            DeviceProof deviceProof) {
        post("/internal/kfe/transaction-approval/wallet-outbound",
                new FinancialWalletOutboundApprovalRequest(
                        actorUserId, ownerUserId, passkeyAssertion, recoveryApproval, deviceProof));
    }

    @Override
    public void approveColdWalletPsbt(Long userId, DeviceProof factor) {
        post("/internal/kfe/transaction-approval/cold-wallet-psbt",
                new FinancialColdWalletPsbtApprovalRequest(userId, factor));
    }

    private void post(String path, Object request) {
        try {
            restTemplate.postForEntity(baseUrl + path, internalJsonEntity(request), Void.class);
        } catch (RestClientResponseException exception) {
            throw mapRemoteAuthFailure(exception);
        }
    }

    private StructuredPlatformException mapRemoteAuthFailure(RestClientResponseException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String message = "Autorizacao transacional rejeitada pelo servidor de autenticacao.";
        String errorCode = ErrorCodes.AUTH_TRANSACTIONAL_AUTH_REQUIRED;
        Object data = null;

        try {
            JsonNode body = objectMapper.readTree(exception.getResponseBodyAsString());
            if (hasText(body.path("message").asText(null))) {
                message = body.path("message").asText();
            }
            if (hasText(body.path("errorCode").asText(null))) {
                errorCode = body.path("errorCode").asText();
            }
            JsonNode dataNode = body.path("data");
            if (!dataNode.isMissingNode() && !dataNode.isNull()) {
                data = objectMapper.convertValue(dataNode, Map.class);
            }
        } catch (Exception ignored) {
            if (hasText(exception.getResponseBodyAsString())) {
                message = exception.getResponseBodyAsString();
            }
        }

        return new StructuredPlatformException(message, status, errorCode, data);
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
