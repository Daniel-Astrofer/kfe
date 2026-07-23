package com.kerosene.kfe.rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Locale;

/**
 * Resolves LNURL-pay (LUD-06) and Lightning Address (LUD-16) to a BOLT11 invoice.
 *
 * <p>Uses the custody RestTemplate (clearnet, short timeouts). Tor-only egress is out of scope here.
 */
@Component
@ConditionalOnProperty(prefix = "lightning.lnd.rest", name = "enabled", havingValue = "true")
public class LnurlPayResolver {

    private static final Logger log = LoggerFactory.getLogger(LnurlPayResolver.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public LnurlPayResolver(
            @Qualifier("custodyRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * @param classified LNURL or LIGHTNING_ADDRESS
     * @param amountSats amount the user intends to pay
     * @return bolt11 payment request
     */
    public String resolveBolt11(LightningDestinationClassifier.Classified classified, long amountSats) {
        if (classified == null) {
            throw new IllegalArgumentException("Lightning destination is required.");
        }
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("amountSats must be positive for LNURL / Lightning Address.");
        }
        String endpoint = switch (classified.kind()) {
            case LNURL -> LnurlBech32.decodeToUrl(classified.value());
            case LIGHTNING_ADDRESS -> lightningAddressToLnurlpUrl(classified.value());
            default -> throw new IllegalArgumentException(
                    "LNURL resolver does not support kind " + classified.kind());
        };
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Could not decode LNURL / Lightning Address to a URL.");
        }

        JsonNode payRequest = httpGetJson(endpoint);
        String tag = text(payRequest, "tag");
        if (tag != null && !tag.isBlank() && !"payRequest".equalsIgnoreCase(tag)) {
            throw new IllegalArgumentException("LNURL tag is not payRequest: " + tag);
        }
        String callback = text(payRequest, "callback");
        if (callback == null || callback.isBlank()) {
            throw new IllegalArgumentException("LNURL payRequest missing callback.");
        }
        long minSendable = longField(payRequest, "minSendable");
        long maxSendable = longField(payRequest, "maxSendable");
        long amountMsat = Math.multiplyExact(amountSats, 1000L);
        if (minSendable > 0L && amountMsat < minSendable) {
            throw new IllegalArgumentException(
                    "Amount below LNURL minSendable (" + (minSendable / 1000L) + " sats).");
        }
        if (maxSendable > 0L && amountMsat > maxSendable) {
            throw new IllegalArgumentException(
                    "Amount above LNURL maxSendable (" + (maxSendable / 1000L) + " sats).");
        }

        String invoiceUrl = UriComponentsBuilder.fromUriString(callback)
                .replaceQueryParam("amount", amountMsat)
                .build(true)
                .toUriString();
        JsonNode invoiceResponse = httpGetJson(invoiceUrl);
        String pr = text(invoiceResponse, "pr");
        if (pr == null || pr.isBlank()) {
            // Some servers use "payment_request"
            pr = text(invoiceResponse, "payment_request");
        }
        if (pr == null || pr.isBlank()) {
            String reason = text(invoiceResponse, "reason", "status");
            throw new IllegalArgumentException(
                    "LNURL callback did not return a BOLT11 invoice"
                            + (reason != null ? ": " + reason : "."));
        }
        String lower = pr.trim().toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("lnbc") || lower.startsWith("lntb") || lower.startsWith("lnbcrt")
                || lower.startsWith("lnsb") || lower.startsWith("lntbs"))) {
            throw new IllegalArgumentException("LNURL callback returned a non-BOLT11 pr.");
        }
        log.info("[LNURL] resolved {} → bolt11 ({} sats)", classified.kind(), amountSats);
        return pr.trim();
    }

    static String lightningAddressToLnurlpUrl(String address) {
        int at = address.lastIndexOf('@');
        if (at <= 0 || at == address.length() - 1) {
            return null;
        }
        String name = address.substring(0, at).trim();
        String domain = address.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || domain.isEmpty()) {
            return null;
        }
        // LUD-16: https://<domain>/.well-known/lnurlp/<name> (http only for .onion)
        boolean onion = domain.endsWith(".onion");
        String scheme = onion ? "http" : "https";
        String encodedName = java.net.URLEncoder
                .encode(name, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return scheme + "://" + domain + "/.well-known/lnurlp/" + encodedName;
    }

    private JsonNode httpGetJson(String url) {
        try {
            URI uri = URI.create(url);
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalArgumentException(
                        "LNURL HTTP " + response.getStatusCode().value() + " for " + safeHost(url));
            }
            return objectMapper.readTree(response.getBody());
        } catch (RestClientException | IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "LNURL fetch failed for " + safeHost(url) + ": " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "LNURL parse failed for " + safeHost(url) + ": " + ex.getMessage(), ex);
        }
    }

    private static String safeHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception ignored) {
            return "endpoint";
        }
    }

    private static String text(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && value.isTextual()) {
                String t = value.asText();
                if (t != null && !t.isBlank()) {
                    return t.trim();
                }
            }
        }
        return null;
    }

    private static long longField(JsonNode node, String field) {
        if (node == null) {
            return 0L;
        }
        JsonNode value = node.path(field);
        if (value.isIntegralNumber()) {
            return Math.max(0L, value.asLong());
        }
        if (value.isTextual()) {
            try {
                return Math.max(0L, Long.parseLong(value.asText().trim()));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
