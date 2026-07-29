package com.kerosene.kfe.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Fail-closed authorization bridge from KFE to a Vault-plane Kerosene Node.
 *
 * <p>The Node manifest signs the member onion host at the discovery port. KFE
 * keeps using its existing Tor+mTLS Vault transport, but refuses every
 * configured Vault URL whose onion host is absent from the verified manifest.
 */
@Component
@ConditionalOnProperty(name = "kfe.kerosene-node.enabled", havingValue = "true")
public final class KfeKeroseneNodeDirectory {

    private final RestTemplate client;
    private final ObjectMapper objectMapper;
    private final String manifestUrl;
    private final String expectedNetwork;
    private final Duration cacheTtl;
    private volatile Snapshot snapshot = new Snapshot(Set.of(), Instant.EPOCH);

    public KfeKeroseneNodeDirectory(
            RestTemplateBuilder builder,
            ObjectMapper objectMapper,
            @Value("${kfe.kerosene-node.base-url}") String baseUrl,
            @Value("${kfe.kerosene-node.network-id}") String expectedNetwork,
            @Value("${kfe.kerosene-node.tls.cert-path}") String certPath,
            @Value("${kfe.kerosene-node.tls.key-path}") String keyPath,
            @Value("${kfe.kerosene-node.tls.ca-path}") String caPath,
            @Value("${kfe.kerosene-node.transport:tor}") String transport,
            @Value("${kfe.kerosene-node.proxy.socks-host:}") String socksHost,
            @Value("${kfe.kerosene-node.proxy.socks-port:9050}") int socksPort,
            @Value("${kfe.kerosene-node.cache-ttl-ms:15000}") long cacheTtlMs) {
        this.objectMapper = objectMapper;
        this.expectedNetwork = requireText(expectedNetwork, "network-id");
        this.cacheTtl = Duration.ofMillis(Math.max(1000, cacheTtlMs));
        String normalized = trimTrailingSlash(requireText(baseUrl, "base-url"));
        this.manifestUrl = normalized + "/v1/membership/current";

        SSLContext sslContext = KfeVaultMeshTlsSupport.buildSslContext(
                certPath, keyPath, caPath, "", "", "PKCS12", "", "", "PKCS12");
        Proxy proxy = KfeVaultMeshTlsSupport.validateTransport(
                transport, socksHost, socksPort, true, List.of(normalized));
        ClientHttpRequestFactory factory =
                KfeVaultMeshTlsSupport.requestFactory(sslContext, true, 3000, 5000, proxy);
        this.client = builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .requestFactory(() -> factory)
                .build();
    }

    public void requireAuthorized(Collection<String> vaultUrls) {
        requireAuthorized(vaultUrls, currentAuthorizedHosts());
    }

    static void requireAuthorized(Collection<String> vaultUrls, Set<String> authorized) {
        for (String vaultUrl : vaultUrls) {
            String host = onionHost(vaultUrl);
            if (!authorized.contains(host)) {
                throw new IllegalStateException(
                        "Vault endpoint is absent from verified Kerosene Node membership: " + host);
            }
        }
    }

    private Set<String> currentAuthorizedHosts() {
        Snapshot current = snapshot;
        if (current.refreshedAt().plus(cacheTtl).isAfter(Instant.now())) {
            return current.hosts();
        }
        synchronized (this) {
            current = snapshot;
            if (current.refreshedAt().plus(cacheTtl).isAfter(Instant.now())) {
                return current.hosts();
            }
            ResponseEntity<String> response =
                    client.exchange(manifestUrl, HttpMethod.GET, null, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Kerosene Node membership is unavailable");
            }
            try {
                Manifest manifest = objectMapper.readValue(response.getBody(), Manifest.class);
                if (!expectedNetwork.equals(manifest.networkId()) || !"vault".equals(manifest.plane())) {
                    throw new IllegalStateException("Kerosene Node network or plane mismatch");
                }
                Set<String> hosts = manifest.members().stream()
                        .map(Member::endpoint)
                        .map(KfeKeroseneNodeDirectory::onionHost)
                        .collect(Collectors.toUnmodifiableSet());
                if (hosts.isEmpty()) {
                    throw new IllegalStateException("Kerosene Node returned an empty Vault roster");
                }
                snapshot = new Snapshot(hosts, Instant.now());
                return hosts;
            } catch (IllegalStateException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException("Invalid Kerosene Node membership response", exception);
            }
        }
    }

    static String onionHost(String rawUrl) {
        URI uri = URI.create(requireText(rawUrl, "Vault URL"));
        String host = uri.getHost();
        String onionLabel = host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("\\.onion$", "");
        boolean isV3Onion = onionLabel.length() == 56
                && onionLabel.chars().allMatch(character ->
                        (character >= 'a' && character <= 'z') || (character >= '2' && character <= '7'));
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || !isV3Onion
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException("Kerosene Node integration accepts only HTTPS onion endpoints");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("kfe.kerosene-node." + name + " is required");
        }
        return value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record Snapshot(Set<String> hosts, Instant refreshedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Manifest(
            @com.fasterxml.jackson.annotation.JsonProperty("network_id") String networkId,
            String plane,
            List<Member> members) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Member(String endpoint) {}
}
