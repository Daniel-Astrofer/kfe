package source.kfe.rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Component("kfeLndRestLightningClient")
@ConditionalOnProperty(prefix = "lightning.lnd.rest", name = "enabled", havingValue = "true")
public class LndRestLightningClient implements LightningClient, LightningPaymentGateway {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String macaroonHex;
    private final int paymentTimeoutSeconds;

    public LndRestLightningClient(
            @Qualifier("lndRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${lightning.lnd.base-url}") String baseUrl,
            @Value("${lightning.lnd.macaroon}") String macaroonHex,
            @Value("${lightning.lnd.payment-timeout-seconds:30}") int paymentTimeoutSeconds) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = sanitize(baseUrl);
        this.macaroonHex = macaroonHex != null ? macaroonHex.trim() : "";
        this.paymentTimeoutSeconds = paymentTimeoutSeconds;
    }

    @Override
    public long getLocalBalance() {
        JsonNode response = get("/v1/balance/channels");
        return nestedLong(response, "local_balance", "sat", "value")
                + longField(response, "local_balance_sat");
    }

    @Override
    public long getRemoteBalance() {
        JsonNode response = get("/v1/balance/channels");
        return nestedLong(response, "remote_balance", "sat", "value")
                + longField(response, "remote_balance_sat");
    }

    @Override
    public long getLightningNodeBalance() {
        JsonNode response = get("/v1/balance/channels");
        long explicit = longField(response, "balance", "balance_sat");
        if (explicit > 0L) {
            return explicit;
        }
        return getLocalBalance() + getRemoteBalance();
    }

    @Override
    public double getNodeUptime() {
        JsonNode response = get("/v1/getinfo");
        return response.path("synced_to_chain").asBoolean(false) ? 1.0d : 0.0d;
    }

    @Override
    public long getLspLatency() {
        return 0L;
    }

    @Override
    public boolean isLive() {
        return !baseUrl.isBlank() && !macaroonHex.isBlank();
    }

    @Override
    public String providerName() {
        return "LND_REST";
    }

    @Override
    public CustodyGateway.PaymentResult payLightning(CustodyGateway.LightningPaymentCommand command) {
        LightningPaymentResult result = payInvoice(
                command.paymentRequest(),
                command.amountSats(),
                command.maxFeeSats());
        return new CustodyGateway.PaymentResult(
                result.paymentHash(),
                null,
                result.paymentHash(),
                result.status(),
                result.feeSats(),
                result.rawPayload());
    }

    public LightningPaymentResult payInvoice(String paymentRequest, long amountSats, long maxFeeSats) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_request", paymentRequest);
        payload.put("timeout_seconds", paymentTimeoutSeconds);
        payload.put("fee_limit_sat", String.valueOf(Math.max(0L, maxFeeSats)));
        payload.put("no_inflight_updates", true);
        if (amountSats > 0L) {
            payload.put("amt", String.valueOf(amountSats));
        }

        JsonNode response = post("/v2/router/send", payload);
        return new LightningPaymentResult(
                text(response, "payment_hash"),
                longField(response, "fee_sat", "fee"),
                text(response, "status"),
                response.toString());
    }

    private JsonNode get(String path) {
        try {
            HttpEntity<Void> request = new HttpEntity<>(headers());
            ResponseEntity<String> response = restTemplate.exchange(baseUrl + path, HttpMethod.GET, request, String.class);
            return parse(response);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode post(String path, Map<String, ?> payload) {
        try {
            HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(payload), headers());
            ResponseEntity<String> response = restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
            return parse(response);
        } catch (Exception ex) {
            throw new IllegalStateException("LND REST request failed on " + path, ex);
        }
    }

    private JsonNode parse(ResponseEntity<String> response) throws Exception {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("LND returned HTTP " + response.getStatusCode());
        }
        return objectMapper.readTree(response.getBody());
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Grpc-Metadata-macaroon", macaroonHex);
        return headers;
    }

    private long nestedLong(JsonNode root, String field, String nestedField, String fallbackField) {
        JsonNode nested = root.path(field);
        long direct = longField(nested, nestedField, fallbackField);
        return Math.max(0L, direct);
    }

    private long longField(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isIntegralNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                try {
                    return Long.parseLong(value.asText());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0L;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String sanitize(String value) {
        String trimmed = value != null ? value.trim() : "";
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public record LightningPaymentResult(
            String paymentHash,
            long feeSats,
            String status,
            String rawPayload) {
    }
}
