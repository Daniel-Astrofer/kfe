package com.kerosene.kfe.rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional Lightning Loop (loopd) REST client for submarine swaps when circular self-pay is insufficient.
 *
 * <p>Enable with {@code lightning.loop.enabled=true} and {@code lightning.loop.base-url}.
 */
@Component
@ConditionalOnProperty(prefix = "lightning.loop", name = "enabled", havingValue = "true")
public class LightningLoopClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String macaroonHex;
    private final long maxSwapFeeSats;
    private final long maxMinerFeeSats;

    public LightningLoopClient(
            @Qualifier("lndRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${lightning.loop.base-url}") String baseUrl,
            @Value("${lightning.loop.macaroon:}") String macaroonHex,
            @Value("${lightning.loop.max-swap-fee-sats:10000}") long maxSwapFeeSats,
            @Value("${lightning.loop.max-miner-fee-sats:5000}") long maxMinerFeeSats) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = sanitize(baseUrl);
        this.macaroonHex = macaroonHex != null ? macaroonHex.trim() : "";
        this.maxSwapFeeSats = Math.max(0L, maxSwapFeeSats);
        this.maxMinerFeeSats = Math.max(0L, maxMinerFeeSats);
    }

    public boolean isLive() {
        return !baseUrl.isBlank();
    }

    public String providerName() {
        return "LIGHTNING_LOOP";
    }

    /**
     * Loop In: on-chain → Lightning (helps when local liquidity is scarce / need inbound fill).
     */
    public LoopResult loopIn(long amountSats) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amt", String.valueOf(amountSats));
        payload.put("max_swap_fee", String.valueOf(maxSwapFeeSats));
        payload.put("max_miner_fee", String.valueOf(maxMinerFeeSats));
        return postLoop("/v1/loop/in", payload, "LOOP_IN");
    }

    /**
     * Loop Out: Lightning → on-chain (optional outgoing channel set to drain excess local).
     */
    public LoopResult loopOut(long amountSats, List<String> outgoingChanIds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amt", String.valueOf(amountSats));
        payload.put("max_swap_fee", String.valueOf(maxSwapFeeSats));
        payload.put("max_miner_fee", String.valueOf(maxMinerFeeSats));
        if (outgoingChanIds != null && !outgoingChanIds.isEmpty()) {
            payload.put(
                    "outgoing_chan_set",
                    outgoingChanIds.stream()
                            .map(id -> {
                                try {
                                    return Long.parseLong(id);
                                } catch (NumberFormatException ex) {
                                    return id;
                                }
                            })
                            .toList());
        }
        return postLoop("/v1/loop/out", payload, "LOOP_OUT");
    }

    private LoopResult postLoop(String path, Map<String, ?> payload, String kind) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (!macaroonHex.isBlank()) {
                headers.set("Grpc-Metadata-macaroon", macaroonHex);
            }
            HttpEntity<String> request =
                    new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
            ResponseEntity<String> response =
                    restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return LoopResult.failed(kind, "HTTP " + response.getStatusCode(), null);
            }
            JsonNode body = objectMapper.readTree(response.getBody());
            String id = text(body, "id", "swap_hash");
            return LoopResult.ok(kind, id, body.toString());
        } catch (Exception ex) {
            return LoopResult.failed(
                    kind, ex.getMessage() != null ? ex.getMessage() : "loop request failed", null);
        }
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && value.isTextual()) {
                return value.asText();
            }
        }
        return null;
    }

    private String sanitize(String value) {
        String trimmed = value != null ? value.trim() : "";
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public record LoopResult(
            boolean succeeded,
            String kind,
            String swapId,
            String message,
            String rawPayload) {

        public static LoopResult ok(String kind, String swapId, String raw) {
            return new LoopResult(true, kind, swapId, "OK", raw);
        }

        public static LoopResult failed(String kind, String message, String raw) {
            return new LoopResult(false, kind, null, message, raw);
        }
    }
}
