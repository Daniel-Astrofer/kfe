package com.kerosene.kfe.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.vaultmesh.VaultMeshDayAdvanceResult;
import com.kerosene.common.vaultmesh.VaultMeshDayStatus;
import com.kerosene.common.vaultmesh.VaultMeshIntent;
import com.kerosene.common.vaultmesh.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.VaultMeshPsbtReceipt;
import com.kerosene.common.vaultmesh.VaultMeshPsbtRequest;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshReshareResult;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP adapter from {@code kfe-service} to the vault-mesh lab/prod node.
 * Maps Intent → {@code POST /sign/{intentId}/{messageHash}} (F3 vault API);
 * day rotation → {@code /v1/day/*} + {@code /v1/reshare/trigger}.
 *
 * <p>Optional client mTLS via {@code kfe.vaultmesh.tls.*} (PEM or keystore/truststore).
 * When TLS is enabled, {@code X-Vault-Token} is omitted (vault mTLS mode refuses static tokens).
 */
@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
public class KfeVaultMeshSettlementClient implements VaultMeshSettlementPort {

    private static final Pattern DAY_STALE =
            Pattern.compile("day_epoch stale:\\s*have\\s+(\\d{4}-\\d{2}-\\d{2}),\\s*need\\s+(\\d{4}-\\d{2}-\\d{2})",
                    Pattern.CASE_INSENSITIVE);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiToken;
    private final boolean tlsEnabled;

    public KfeVaultMeshSettlementClient(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            @Value("${kfe.vaultmesh.base-url:http://127.0.0.1:7701}") String baseUrl,
            @Value("${kfe.vaultmesh.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${kfe.vaultmesh.read-timeout-ms:5000}") long readTimeoutMs,
            @Value("${kfe.vaultmesh.api-token:}") String apiToken,
            @Value("${kfe.vaultmesh.tls.enabled:false}") boolean tlsEnabled,
            @Value("${kfe.vaultmesh.tls.cert-path:}") String tlsCertPath,
            @Value("${kfe.vaultmesh.tls.key-path:}") String tlsKeyPath,
            @Value("${kfe.vaultmesh.tls.ca-path:}") String tlsCaPath,
            @Value("${kfe.vaultmesh.tls.keystore-path:}") String tlsKeystorePath,
            @Value("${kfe.vaultmesh.tls.keystore-password:}") String tlsKeystorePassword,
            @Value("${kfe.vaultmesh.tls.keystore-type:PKCS12}") String tlsKeystoreType,
            @Value("${kfe.vaultmesh.tls.truststore-path:}") String tlsTruststorePath,
            @Value("${kfe.vaultmesh.tls.truststore-password:}") String tlsTruststorePassword,
            @Value("${kfe.vaultmesh.tls.truststore-type:PKCS12}") String tlsTruststoreType,
            @Value("${kfe.vaultmesh.tls.hostname-verification:true}") boolean tlsHostnameVerification) {
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.tlsEnabled = KfeVaultMeshTlsSupport.tlsConfigured(
                tlsEnabled, tlsCertPath, tlsKeyPath, tlsCaPath, tlsKeystorePath, tlsTruststorePath);

        RestTemplateBuilder builder = restTemplateBuilder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs));
        if (this.tlsEnabled) {
            SSLContext sslContext = KfeVaultMeshTlsSupport.buildSslContext(
                    tlsCertPath,
                    tlsKeyPath,
                    tlsCaPath,
                    tlsKeystorePath,
                    tlsKeystorePassword,
                    tlsKeystoreType,
                    tlsTruststorePath,
                    tlsTruststorePassword,
                    tlsTruststoreType);
            ClientHttpRequestFactory factory = KfeVaultMeshTlsSupport.requestFactory(
                    sslContext,
                    tlsHostnameVerification,
                    (int) Math.min(connectTimeoutMs, Integer.MAX_VALUE),
                    (int) Math.min(readTimeoutMs, Integer.MAX_VALUE));
            this.restTemplate = builder.requestFactory(() -> factory).build();
        } else {
            this.restTemplate = builder.build();
        }
    }

    /** Test / lab helper: plaintext client (no mTLS). */
    KfeVaultMeshSettlementClient(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            String baseUrl,
            long connectTimeoutMs,
            long readTimeoutMs,
            String apiToken) {
        this(
                restTemplateBuilder,
                objectMapper,
                baseUrl,
                connectTimeoutMs,
                readTimeoutMs,
                apiToken,
                false,
                "",
                "",
                "",
                "",
                "",
                "PKCS12",
                "",
                "",
                "PKCS12",
                true);
    }

    boolean tlsEnabled() {
        return tlsEnabled;
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
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(path, new HttpEntity<>(authHeaders(false)), Map.class);
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

    @Override
    public VaultMeshDepositInfo getUsersDepositAddress() {
        String path = baseUrl + "/v1/bitcoin/deposit";
        try {
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response =
                    restTemplate.exchange(
                            path, HttpMethod.GET, new HttpEntity<>(authHeaders(false)), Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null) {
                return null;
            }
            String address = body.get("address") == null ? null : String.valueOf(body.get("address"));
            if (address == null || address.isBlank()) {
                return null;
            }
            // Enforce USERS deposit policy: shared Taproot tb1p only.
            if (!address.trim().toLowerCase(Locale.ROOT).startsWith("tb1p")) {
                return null;
            }
            return new VaultMeshDepositInfo(
                    address.trim(),
                    body.get("descriptor") == null ? null : String.valueOf(body.get("descriptor")),
                    body.get("scheme") == null ? null : String.valueOf(body.get("scheme")),
                    body.get("output_pubkey") == null ? null : String.valueOf(body.get("output_pubkey")),
                    body.get("xonly_pubkey") == null ? null : String.valueOf(body.get("xonly_pubkey")),
                    body.get("network") == null ? null : String.valueOf(body.get("network")));
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public VaultMeshPsbtReceipt signPsbt(VaultMeshPsbtRequest request) {
        if (request == null || request.intentId() == null || request.intentId().isBlank()) {
            return psbtRejected("INVALID_INTENT", null);
        }
        if (request.psbtBase64() == null || request.psbtBase64().isBlank()) {
            return psbtRejected("EMPTY_PSBT", request.intentId());
        }
        // Shared Taproot deposit key is USERS-only until per-bucket keys exist.
        // CHANNELS/INFRA must not escape via the same tr()/tb1p (vault assert_shared_taproot_bucket).
        String bucket = request.bucket() == null || request.bucket().isBlank()
                ? "USERS"
                : request.bucket().trim().toUpperCase(Locale.ROOT);
        if (!"USERS".equals(bucket)) {
            return psbtRejected("MESH_BUCKET_NOT_SHARED_TAPROOT:" + bucket, request.intentId());
        }
        String path = baseUrl + "/v1/bitcoin/sign-psbt";
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("session_id", firstNonBlank(request.sessionId(), request.intentId()));
            payload.put("psbt", request.psbtBase64());
            payload.put("intent_id", request.intentId());
            payload.put("bucket", bucket);
            payload.put("destination", request.destination() == null ? "" : request.destination());
            payload.put("amount_sats", request.amountSats());
            String json = objectMapper.writeValueAsString(payload);
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(path, new HttpEntity<>(json, authHeaders(true)), Map.class);
            return toPsbtReceipt(request.intentId(), response.getBody());
        } catch (RestClientResponseException ex) {
            Map<?, ?> body = parseBody(ex.getResponseBodyAsString());
            if (body != null && body.get("error") != null) {
                return psbtMeshError(request.intentId(), String.valueOf(body.get("error")));
            }
            return psbtRejected("MESH_HTTP_" + ex.getStatusCode().value(), request.intentId());
        } catch (Exception ex) {
            return psbtRejected("MESH_HTTP_ERROR:" + ex.getClass().getSimpleName(), request.intentId());
        }
    }

    @Override
    public VaultMeshDayStatus getDayStatus() {
        String utcToday = LocalDate.now(ZoneOffset.UTC).toString();
        String path = baseUrl + "/v1/day/current";
        try {
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = restTemplate.exchange(
                    path, HttpMethod.GET, new HttpEntity<>(authHeaders(false)), Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null || body.get("day_epoch") == null) {
                return VaultMeshDayStatus.failed("EMPTY_DAY_RESPONSE");
            }
            String day = String.valueOf(body.get("day_epoch")).trim();
            if (day.compareTo(utcToday) >= 0) {
                return VaultMeshDayStatus.upToDate(day);
            }
            return VaultMeshDayStatus.stale(day, utcToday);
        } catch (RestClientResponseException ex) {
            Map<?, ?> body = parseBody(ex.getResponseBodyAsString());
            String err = body != null && body.get("error") != null
                    ? String.valueOf(body.get("error"))
                    : "MESH_HTTP_" + ex.getStatusCode().value();
            Matcher stale = DAY_STALE.matcher(err);
            if (stale.find()) {
                return VaultMeshDayStatus.stale(stale.group(1), stale.group(2));
            }
            return VaultMeshDayStatus.failed(err);
        } catch (Exception ex) {
            return VaultMeshDayStatus.failed("MESH_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }
    }

    @Override
    public VaultMeshDayAdvanceResult voteDay(String voter, String dayEpoch) {
        if (dayEpoch == null || dayEpoch.isBlank()) {
            return VaultMeshDayAdvanceResult.failed("INVALID_VOTE");
        }
        String path = baseUrl + "/v1/day/vote";
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            // Voter is derived server-side from authenticated vault identity.
            // Do not spoof peer vault ids under shared lab token / mTLS binding.
            if (voter != null && !voter.isBlank()) {
                payload.put("voter", voter.trim());
            }
            payload.put("day_epoch", dayEpoch.trim());
            String json = objectMapper.writeValueAsString(payload);
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(path, new HttpEntity<>(json, authHeaders(true)), Map.class);
            Map<?, ?> body = response.getBody();
            if (body != null && body.get("error") != null) {
                return VaultMeshDayAdvanceResult.failed(String.valueOf(body.get("error")));
            }
            return VaultMeshDayAdvanceResult.ok(dayEpoch.trim(), false);
        } catch (RestClientResponseException ex) {
            return dayHttpFailure(ex);
        } catch (Exception ex) {
            return VaultMeshDayAdvanceResult.failed("MESH_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }
    }

    @Override
    public VaultMeshDayAdvanceResult advanceDay() {
        String path = baseUrl + "/v1/day/advance";
        try {
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(path, new HttpEntity<>(authHeaders(false)), Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null) {
                return VaultMeshDayAdvanceResult.failed("EMPTY_ADVANCE_RESPONSE");
            }
            if (body.get("error") != null) {
                return VaultMeshDayAdvanceResult.failed(String.valueOf(body.get("error")));
            }
            String day = body.get("day_epoch") == null ? null : String.valueOf(body.get("day_epoch"));
            boolean advanced = body.get("advanced") == null || Boolean.parseBoolean(String.valueOf(body.get("advanced")));
            return VaultMeshDayAdvanceResult.ok(day, advanced);
        } catch (RestClientResponseException ex) {
            return dayHttpFailure(ex);
        } catch (Exception ex) {
            return VaultMeshDayAdvanceResult.failed("MESH_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }
    }

    @Override
    public VaultMeshReshareResult triggerReshare(String reason) {
        String path = baseUrl + "/v1/reshare/trigger";
        String effectiveReason = reason == null || reason.isBlank() ? "kfe-day-rotation" : reason.trim();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", effectiveReason);
            String json = objectMapper.writeValueAsString(payload);
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(path, new HttpEntity<>(json, authHeaders(true)), Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null) {
                return VaultMeshReshareResult.failed("EMPTY_RESHARE_RESPONSE");
            }
            if (body.get("error") != null) {
                return VaultMeshReshareResult.failed(String.valueOf(body.get("error")));
            }
            String policy = body.get("policy") == null ? null : String.valueOf(body.get("policy"));
            String respReason = body.get("reason") == null ? effectiveReason : String.valueOf(body.get("reason"));
            return VaultMeshReshareResult.ok(policy, respReason);
        } catch (RestClientResponseException ex) {
            Map<?, ?> body = parseBody(ex.getResponseBodyAsString());
            if (body != null && body.get("error") != null) {
                return VaultMeshReshareResult.failed(String.valueOf(body.get("error")));
            }
            return VaultMeshReshareResult.failed("MESH_HTTP_" + ex.getStatusCode().value());
        } catch (Exception ex) {
            return VaultMeshReshareResult.failed("MESH_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }
    }

    private HttpHeaders authHeaders(boolean json) {
        HttpHeaders headers = new HttpHeaders();
        if (json) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        // mTLS identity replaces X-Vault-Token; vault MutualTlsAuthAdapter refuses the header.
        if (!tlsEnabled && !apiToken.isEmpty()) {
            headers.set("X-Vault-Token", apiToken);
        }
        return headers;
    }

    private VaultMeshDayAdvanceResult dayHttpFailure(RestClientResponseException ex) {
        Map<?, ?> body = parseBody(ex.getResponseBodyAsString());
        if (body != null && body.get("error") != null) {
            return VaultMeshDayAdvanceResult.failed(String.valueOf(body.get("error")));
        }
        return VaultMeshDayAdvanceResult.failed("MESH_HTTP_" + ex.getStatusCode().value());
    }

    private VaultMeshPsbtReceipt toPsbtReceipt(String intentId, Map<?, ?> body) {
        if (body == null) {
            return psbtRejected("EMPTY_RESPONSE", intentId);
        }
        if (body.get("error") != null) {
            return psbtMeshError(intentId, String.valueOf(body.get("error")));
        }
        Object signed = body.get("signed_psbt");
        if (signed == null || String.valueOf(signed).isBlank()) {
            return psbtRejected("MISSING_SIGNED_PSBT", intentId);
        }
        Object proof = body.get("signature");
        if (proof == null) {
            proof = body.get("session_id");
        }
        return new VaultMeshPsbtReceipt(
                intentId,
                VaultMeshReceipt.Status.ACCEPTED,
                null,
                String.valueOf(signed),
                proof == null ? null : String.valueOf(proof),
                Instant.now().toEpochMilli());
    }

    private static VaultMeshPsbtReceipt psbtMeshError(String intentId, String reason) {
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("fail-stop") || lower.contains("fail_stop") || lower.contains("online <")) {
            return new VaultMeshPsbtReceipt(
                    intentId,
                    VaultMeshReceipt.Status.FAIL_STOP,
                    reason,
                    null,
                    null,
                    Instant.now().toEpochMilli());
        }
        return psbtRejected(reason, intentId);
    }

    private static VaultMeshPsbtReceipt psbtRejected(String reason, String intentId) {
        return new VaultMeshPsbtReceipt(
                intentId,
                VaultMeshReceipt.Status.REJECTED,
                reason,
                null,
                null,
                Instant.now().toEpochMilli());
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null ? "" : fallback.trim();
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
