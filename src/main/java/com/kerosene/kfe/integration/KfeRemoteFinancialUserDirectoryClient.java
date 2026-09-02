package com.kerosene.kfe.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.kerosene.common.security.workload.InternalServiceRestTemplateFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.common.financial.FinancialUserDirectoryLookupRequest;
import com.kerosene.common.financial.FinancialUserDirectoryPort;

import java.util.Locale;
import java.util.Optional;

@Component
@Profile("kfe")
@ConditionalOnProperty(name = "kfe.remote.user-directory.enabled", havingValue = "true", matchIfMissing = true)
public class KfeRemoteFinancialUserDirectoryClient implements FinancialUserDirectoryPort {

    private static final String DEFAULT_BASE_URL = "http://server:8080";
    private static final String LOOKUP_PATH = "/internal/kfe/user-directory/lookup";
    private static final ParameterizedTypeReference<ApiResponse<FinancialUserHandle>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public KfeRemoteFinancialUserDirectoryClient(
            InternalServiceRestTemplateFactory restTemplateFactory,
            @Value("${auth.remote.base-url:http://server:8080}") String baseUrl,
            @Value("${auth.remote.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${auth.remote.read-timeout-ms:5000}") long readTimeoutMs) {
        InternalServiceRestTemplateFactory.ConfiguredClient client = restTemplateFactory.create(
                baseUrl, DEFAULT_BASE_URL, connectTimeoutMs, readTimeoutMs);
        this.restTemplate = client.restTemplate();
        this.baseUrl = client.baseUrl();
    }

    @Override
    public Optional<FinancialUserHandle> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return lookup(FinancialUserDirectoryLookupRequest.byUsername(normalized));
    }

    @Override
    public Optional<FinancialUserHandle> findById(Long userId) {
        if (userId == null || userId <= 0L) {
            return Optional.empty();
        }
        return lookup(FinancialUserDirectoryLookupRequest.byUserId(userId));
    }

    private Optional<FinancialUserHandle> lookup(FinancialUserDirectoryLookupRequest request) {
        try {
            ResponseEntity<ApiResponse<FinancialUserHandle>> response = restTemplate.exchange(
                    baseUrl + LOOKUP_PATH,
                    HttpMethod.POST,
                    internalJsonEntity(request),
                    RESPONSE_TYPE);
            ApiResponse<FinancialUserHandle> body = response.getBody();
            if (body == null || !body.isSuccess() || !isValidHandle(body.getData())) {
                throw unavailable("Core user directory returned an invalid response.", null);
            }
            return Optional.of(body.getData());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw unavailable("Core user directory is unavailable.", exception);
        } catch (RestClientException exception) {
            throw unavailable("Core user directory is unavailable.", exception);
        }
    }

    private <T> HttpEntity<T> internalJsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private ResponseStatusException unavailable(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    private boolean isValidHandle(FinancialUserHandle handle) {
        return handle != null
                && handle.id() != null
                && handle.id() > 0L
                && handle.username() != null
                && !handle.username().isBlank();
    }

}
