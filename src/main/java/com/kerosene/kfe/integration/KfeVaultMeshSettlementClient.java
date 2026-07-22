package source.kfe.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.vaultmesh.VaultMeshIntent;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP adapter from {@code kfe-service} to the vault-mesh lab/prod node.
 * Maps Intent → {@code POST /sign/{intentId}/{messageHash}} (F3 vault API).
 */
@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
public class KfeVaultMeshSettlementClient implements VaultMeshSettlementPort {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public KfeVaultMeshSettlementClient(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            @Value("${kfe.vaultmesh.base-url:http://127.0.0.1:7701}") String baseUrl,
            @Value("${kfe.vaultmesh.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${kfe.vaultmesh.read-timeout-ms:5000}") long readTimeoutMs) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    @Override
    public VaultMeshReceipt submitIntent(VaultMeshIntent intent) {
        if (intent == null || intent.intentId() == null || intent.intentId().isBlank()) {
            return rejected("INVALID_INTENT", null);
        }
        String messageHash = messageHash(intent);
        String sessionId = UriUtils.encodePathSegment(intent.intentId().trim(), StandardCharsets.UTF_8);
        String path = baseUrl + "/sign/" + sessionId + "/" + messageHash;
        try {
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = restTemplate.postForEntity(path, null, Map.class);
            return toReceipt(intent.intentId(), response.getBody());
        } catch (RestClientResponseException ex) {
            Map<?, ?> body = parseBody(ex.getResponseBodyAsString());
            if (body != null && body.get("error") != null) {
                return meshError(intent.intentId(), String.valueOf(body.get("error")));
            }
            return rejected("MESH_HTTP_" + ex.getStatusCode().value(), intent.intentId());
        } catch (Exception ex) {
            return rejected("MESH_HTTP_ERROR:" + ex.getClass().getSimpleName(), intent.intentId());
        }
    }

    private VaultMeshReceipt toReceipt(String intentId, Map<?, ?> body) {
        if (body == null) {
            return rejected("EMPTY_RESPONSE", intentId);
        }
        if (body.get("error") != null) {
            return meshError(intentId, String.valueOf(body.get("error")));
        }
        Object value = body.get("value");
        String proof = value == null ? String.valueOf(body.get("session_id")) : String.valueOf(value);
        return new VaultMeshReceipt(
                intentId,
                VaultMeshReceipt.Status.ACCEPTED,
                null,
                proof,
                Instant.now().toEpochMilli());
    }

    private Map<?, ?> parseBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    private static VaultMeshReceipt meshError(String intentId, String reason) {
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("fail-stop") || lower.contains("fail_stop") || lower.contains("online <")) {
            return new VaultMeshReceipt(
                    intentId,
                    VaultMeshReceipt.Status.FAIL_STOP,
                    reason,
                    null,
                    Instant.now().toEpochMilli());
        }
        return rejected(reason, intentId);
    }

    static String messageHash(VaultMeshIntent intent) {
        String material = String.join(
                "|",
                nullToEmpty(intent.intentId()),
                nullToEmpty(intent.bucket()),
                nullToEmpty(intent.destination()),
                Long.toString(intent.amountSats()),
                nullToEmpty(intent.policyHash()),
                Long.toString(intent.createdAtEpochMs()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static VaultMeshReceipt rejected(String reason, String intentId) {
        return new VaultMeshReceipt(
                intentId,
                VaultMeshReceipt.Status.REJECTED,
                reason,
                null,
                Instant.now().toEpochMilli());
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:7701";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
