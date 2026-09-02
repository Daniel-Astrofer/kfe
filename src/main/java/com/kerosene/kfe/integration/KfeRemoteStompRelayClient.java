package com.kerosene.kfe.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.kerosene.common.security.workload.InternalServiceRestTemplateFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import com.kerosene.common.financial.StompUserPublishRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Best-effort HTTP bridge to Core STOMP when KFE runs standalone (no in-process broker).
 */
@Component
@Profile("kfe")
@ConditionalOnProperty(name = "kfe.remote.stomp-relay.enabled", havingValue = "true", matchIfMissing = true)
public class KfeRemoteStompRelayClient {

    private static final Logger log = LoggerFactory.getLogger(KfeRemoteStompRelayClient.class);
    private static final String DEFAULT_BASE_URL = "http://server:8080";
    private static final String PATH = "/internal/kfe/stomp/publish";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public KfeRemoteStompRelayClient(
            InternalServiceRestTemplateFactory restTemplateFactory,
            ObjectMapper objectMapper,
            @Value("${auth.remote.base-url:http://server:8080}") String baseUrl,
            @Value("${auth.remote.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${auth.remote.read-timeout-ms:5000}") long readTimeoutMs) {
        InternalServiceRestTemplateFactory.ConfiguredClient client = restTemplateFactory.create(
                baseUrl, DEFAULT_BASE_URL, connectTimeoutMs, readTimeoutMs);
        this.restTemplate = client.restTemplate();
        this.objectMapper = objectMapper;
        this.baseUrl = client.baseUrl();
    }

    public void publishToUser(Long userId, String destination, Object payload) {
        if (userId == null || destination == null || destination.isBlank() || payload == null) {
            return;
        }
        Map<String, Object> body = toMap(payload);
        if (body.isEmpty()) {
            return;
        }
        post(new StompUserPublishRequest(userId, destination, body));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return copy;
        }
        return objectMapper.convertValue(payload, Map.class);
    }

    private void post(StompUserPublishRequest request) {
        HttpEntity<StompUserPublishRequest> entity = internalJsonEntity(request);
        try {
            restTemplate.postForEntity(baseUrl + PATH, entity, Void.class);
        } catch (RestClientResponseException exception) {
            log.warn(
                    "[KFE STOMP] auth server rejected {} for user {} dest {} with HTTP {} — continuing",
                    PATH,
                    request.userId(),
                    request.destination(),
                    exception.getStatusCode().value());
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE STOMP] failed to POST {} for user {} dest {} ({}): {} — continuing",
                    PATH,
                    request.userId(),
                    request.destination(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
        }
    }

    private <T> HttpEntity<T> internalJsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

}
