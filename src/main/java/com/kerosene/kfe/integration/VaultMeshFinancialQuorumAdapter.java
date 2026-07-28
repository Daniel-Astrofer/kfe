package com.kerosene.kfe.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.FinancialQuorumPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
public final class VaultMeshFinancialQuorumAdapter implements FinancialQuorumPort {

    private static final HexFormat HEX = HexFormat.of();
    private static final String DOMAIN = "kerosene-financial-quorum-v1";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String coordinatorUrl;
    private final List<String> vaultUrls;
    private final String apiToken;
    private final int memberCount;
    private final int threshold;

    public VaultMeshFinancialQuorumAdapter(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            @Value("${kfe.vaultmesh.base-url}") String coordinatorUrl,
            @Value("${kfe.vaultmesh.urls}") String vaultUrls,
            @Value("${kfe.vaultmesh.api-token:}") String apiToken,
            @Value("${kfe.vaultmesh.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${kfe.vaultmesh.read-timeout-ms:10000}") long readTimeoutMs,
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
            @Value("${kfe.vaultmesh.tls.hostname-verification:true}") boolean hostnameVerification,
            @Value("${kfe.vaultmesh.constitution.member-count:3}") int memberCount,
            @Value("${kfe.vaultmesh.constitution.threshold:2}") int threshold) {
        this.objectMapper = objectMapper;
        this.coordinatorUrl = trimUrl(coordinatorUrl);
        this.vaultUrls = parseUrls(vaultUrls, this.coordinatorUrl);
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.memberCount = memberCount;
        this.threshold = threshold;
        if (memberCount < 1 || threshold < 1 || threshold > memberCount
                || this.vaultUrls.size() != memberCount) {
            throw new IllegalStateException("Vault financial quorum roster/threshold is invalid");
        }
        RestTemplateBuilder builder = restTemplateBuilder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs));
        if (KfeVaultMeshTlsSupport.tlsConfigured(
                tlsEnabled, tlsCertPath, tlsKeyPath, tlsCaPath, tlsKeystorePath, tlsTruststorePath)) {
            SSLContext sslContext = KfeVaultMeshTlsSupport.buildSslContext(
                    tlsCertPath, tlsKeyPath, tlsCaPath,
                    tlsKeystorePath, tlsKeystorePassword, tlsKeystoreType,
                    tlsTruststorePath, tlsTruststorePassword, tlsTruststoreType);
            ClientHttpRequestFactory factory =
                    KfeVaultMeshTlsSupport.requestFactory(
                            sslContext,
                            hostnameVerification,
                            (int) Math.min(connectTimeoutMs, Integer.MAX_VALUE),
                            (int) Math.min(readTimeoutMs, Integer.MAX_VALUE));
            builder = builder.requestFactory(() -> factory);
        } else if (tlsEnabled) {
            throw new IllegalStateException("Vault financial quorum requires complete mTLS material");
        }
        this.restTemplate = builder.build();
    }

    @Override
    public QuorumDecision requireThresholdConsensus(Proposal proposal) {
        if (proposal == null || proposal.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Live financial quorum proposal required");
        }
        byte[] digest = canonicalDigest(proposal);
        String pinnedGroupKey = requireThresholdGroupKeyAgreement();
        Map<String, Object> request = Map.of(
                "proposal_hash", proposal.proposalHash(),
                "constitution_hash", proposal.constitutionHash(),
                "constitution_epoch", proposal.constitutionEpoch(),
                "submitted_at_epoch_ms", proposal.submittedAt().toEpochMilli(),
                "expires_at_epoch_ms", proposal.expiresAt().toEpochMilli());
        JsonNode response = postJson(coordinatorUrl + "/v1/financial-quorum", request);
        requireText(response, "decision", "ACCEPTED");
        requireText(response, "proposal_hash", proposal.proposalHash());
        requireText(response, "constitution_hash", proposal.constitutionHash());
        requireLong(response, "constitution_epoch", proposal.constitutionEpoch());
        requireLong(response, "configured_members", memberCount);
        requireLong(response, "required_threshold", threshold);
        requireText(response, "signed_digest", HEX.formatHex(digest));

        byte[] responseKey = decodeHex(response.path("verifying_key").asText(), "verifying_key");
        byte[] pinnedKey = decodeHex(pinnedGroupKey, "pinned output_pubkey");
        if (!xOnly(responseKey).equals(xOnly(pinnedKey))) {
            throw new IllegalStateException("Financial quorum proof key differs from threshold-pinned USERS key");
        }
        byte[] signature = decodeHex(response.path("aggregate_proof").asText(), "aggregate_proof");
        if (!Bip340Verifier.verify(digest, responseKey, signature)) {
            throw new IllegalStateException("Financial quorum FROST/BIP340 proof verification failed");
        }

        Set<String> accepted = textSet(response.path("accepted_members"));
        Set<String> rejected = textSet(response.path("rejected_members"));
        Set<String> unavailable = textSet(response.path("unavailable_members"));
        Instant decidedAt = Instant.ofEpochMilli(response.path("decided_at_epoch_ms").asLong());
        return new QuorumDecision(
                Decision.ACCEPTED,
                proposal.proposalHash(),
                proposal.constitutionHash(),
                proposal.constitutionEpoch(),
                memberCount,
                threshold,
                accepted,
                rejected,
                unavailable,
                response.path("aggregate_proof").asText(),
                decidedAt);
    }

    @Override
    @Deprecated(forRemoval = true)
    public Result requireHealthyUnanimousConsensus(String proposalHash) {
        if (proposalHash == null || !proposalHash.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("proposalHash must be a canonical SHA-256 hex digest");
        }
        JsonNode epoch = getJson(coordinatorUrl + "/v1/financial-quorum/context");
        Instant submittedAt = Instant.now();
        QuorumDecision decision = requireThresholdConsensus(new Proposal(
                proposalHash,
                epoch.path("constitution_hash").asText(),
                epoch.path("constitution_epoch").asLong(),
                submittedAt,
                submittedAt.plusSeconds(15)));
        return new Result(decision.acceptedMembers().size(), decision.configuredMembers());
    }

    private String requireThresholdGroupKeyAgreement() {
        Map<String, Integer> counts = new HashMap<>();
        List<RuntimeException> failures = new ArrayList<>();
        for (String url : vaultUrls) {
            try {
                String key = getJson(url + "/v1/bitcoin/deposit?bucket=USERS")
                        .path("output_pubkey").asText().toLowerCase();
                if (!key.matches("[0-9a-f]{64}")) {
                    throw new IllegalStateException("vault returned invalid USERS output_pubkey");
                }
                counts.merge(key, 1, Integer::sum);
            } catch (RuntimeException exception) {
                failures.add(exception);
            }
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No threshold agreement on USERS FROST group key; failures=" + failures.size()));
    }

    private JsonNode getJson(String url) {
        try {
            return objectMapper.readTree(restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers()), String.class).getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("Vault financial quorum GET failed", exception);
        }
    }

    private JsonNode postJson(String url, Object body) {
        try {
            return objectMapper.readTree(restTemplate.postForObject(
                    url, new HttpEntity<>(body, headers()), String.class));
        } catch (Exception exception) {
            throw new IllegalStateException("Vault financial quorum POST failed", exception);
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!apiToken.isEmpty()) {
            headers.set("X-Vault-Token", apiToken);
        }
        return headers;
    }

    private static byte[] canonicalDigest(Proposal proposal) {
        String value = DOMAIN + "|" + proposal.proposalHash() + "|" + proposal.constitutionHash()
                + "|" + proposal.constitutionEpoch() + "|" + proposal.submittedAt().toEpochMilli()
                + "|" + proposal.expiresAt().toEpochMilli();
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static Set<String> textSet(JsonNode node) {
        Set<String> values = new HashSet<>();
        node.forEach(value -> values.add(value.asText()));
        return Set.copyOf(values);
    }

    private static void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(node.path(field).asText())) {
            throw new IllegalStateException("Financial quorum response mismatch: " + field);
        }
    }

    private static void requireLong(JsonNode node, String field, long expected) {
        if (node.path(field).asLong(Long.MIN_VALUE) != expected) {
            throw new IllegalStateException("Financial quorum response mismatch: " + field);
        }
    }

    private static byte[] decodeHex(String value, String field) {
        try {
            return HEX.parseHex(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid hex in financial quorum " + field, exception);
        }
    }

    private static String xOnly(byte[] key) {
        byte[] normalized = key.length == 33 ? Arrays.copyOfRange(key, 1, 33) : key;
        return HEX.formatHex(normalized);
    }

    private static List<String> parseUrls(String csv, String fallback) {
        List<String> values = Arrays.stream(csv == null ? new String[0] : csv.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(VaultMeshFinancialQuorumAdapter::trimUrl)
                .distinct().toList();
        return values.isEmpty() ? List.of(fallback) : values;
    }

    private static String trimUrl(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Vault financial quorum URL is required");
        }
        return result;
    }
}
