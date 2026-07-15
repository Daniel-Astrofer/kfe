package source.kfe.rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Primary
@Component("kfeBtcpayCustodyGateway")
@ConditionalOnProperty(prefix = "btcpay", name = "enabled", havingValue = "true")
public class BtcPayServerCustodyGateway implements CustodyGateway {

    private static final BigDecimal SATOSHIS_PER_BITCOIN = new BigDecimal("100000000");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LndRestLightningClient lndRestLightningClient;
    private final String baseUrl;
    private final String apiKey;
    private final String storeId;
    private final String onchainPaymentMethodId;
    private final String lightningPaymentMethodId;
    private final String invoiceCancelPath;

    public BtcPayServerCustodyGateway(
            @Qualifier("btcpayRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<LndRestLightningClient> lndRestLightningClient,
            @Value("${btcpay.base-url}") String baseUrl,
            @Value("${btcpay.api-key}") String apiKey,
            @Value("${btcpay.store-id}") String storeId,
            @Value("${btcpay.onchain-payment-method-id:BTC-CHAIN}") String onchainPaymentMethodId,
            @Value("${btcpay.lightning-payment-method-id:BTC-LN}") String lightningPaymentMethodId,
            @Value("${btcpay.invoice-cancel-path:}") String invoiceCancelPath) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.lndRestLightningClient = lndRestLightningClient.getIfAvailable();
        this.baseUrl = sanitize(baseUrl);
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.storeId = storeId != null ? storeId.trim() : "";
        this.onchainPaymentMethodId = onchainPaymentMethodId;
        this.lightningPaymentMethodId = lightningPaymentMethodId;
        this.invoiceCancelPath = invoiceCancelPath != null ? invoiceCancelPath.trim() : "";
    }

    @Override
    public boolean isLive() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !storeId.isBlank();
    }

    @Override
    public String providerName() {
        return "BTCPAY";
    }

    @Override
    public GeneratedOnchainAddress createOnchainAddress(OnchainAddressCommand command) {
        JsonNode response = post(
                "/api/v1/stores/" + storeId + "/payment-methods/" + onchainPaymentMethodId + "/wallet/generate",
                Map.of());
        String address = text(response, "address", "depositAddress", "destination");
        return new GeneratedOnchainAddress(
                address,
                text(response, "walletId", "walletReference", "accountReference"),
                text(response, "address", "depositAddress", "destination"));
    }

    @Override
    public GeneratedLightningInvoice createLightningInvoice(LightningInvoiceCommand command) {
        int expirationMinutes = Math.max(1, (int) Math.ceil(command.expiresInSeconds() / 60d));
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("expirationMinutes", expirationMinutes);
        checkout.put("redirectAutomatically", false);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderId", "kerosene-ln-" + UUID.randomUUID());
        metadata.put("internalUserId", String.valueOf(command.userId()));
        metadata.put("internalWalletId", String.valueOf(command.walletId()));
        metadata.put("walletName", command.walletName());
        metadata.put("memo", safeText(command.memo()));
        metadata.put("source", "KEROSENE");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", satsToBtc(command.amountSats()).toPlainString());
        payload.put("currency", "BTC");
        payload.put("checkout", checkout);
        payload.put("metadata", metadata);

        JsonNode invoice = post("/api/v1/stores/" + storeId + "/invoices", payload);
        String invoiceId = text(invoice, "id", "invoiceId");
        JsonNode paymentMethod = invoiceId != null ? findPaymentMethod(invoiceId, lightningPaymentMethodId) : null;

        return new GeneratedLightningInvoice(
                text(paymentMethod, "paymentRequest", "bolt11", "invoice", "paymentLink"),
                text(paymentMethod, "paymentHash", "hash"),
                text(paymentMethod, "lightningAddress", "lnAddress"),
                invoiceId,
                parseDateTime(invoice, "expirationTime", "expiresAt"));
    }

    @Override
    public IncomingLightningInvoiceStatus getLightningInvoiceStatus(LightningInvoiceStatusCommand command) {
        String invoiceId = firstNonBlank(command.providerReference(), command.paymentHash());
        JsonNode invoice = get("/api/v1/stores/" + storeId + "/invoices/" + invoiceId);
        JsonNode paymentMethod = invoiceId != null ? findPaymentMethod(invoiceId, lightningPaymentMethodId) : null;

        String status = normalizeInvoiceStatus(invoice);
        long receivedSats = btcNodeToSats(invoice.path("amount"));
        if (receivedSats <= 0L) {
            receivedSats = btcNodeToSats(paymentMethod == null ? null : paymentMethod.path("amount"));
        }

        return new IncomingLightningInvoiceStatus(
                status,
                receivedSats > 0L ? receivedSats : null,
                parseDateTime(invoice, "monitoringExpiration", "statusTime", "expiresAt"),
                mergePayload(invoice, paymentMethod));
    }

    @Override
    public boolean cancelLightningInvoice(LightningInvoiceCancellationCommand command) {
        String invoiceId = firstNonBlank(command.providerReference(), command.paymentHash());
        if (invoiceId == null || invoiceId.isBlank() || invoiceCancelPath.isBlank()) {
            return false;
        }
        post(invoiceCancelPath.replace("{invoiceId}", invoiceId), Map.of("invoiceId", invoiceId));
        return true;
    }

    @Override
    public PaymentResult sendOnchain(OnchainPaymentCommand command) {
        throw new IllegalStateException("On-chain payments are handled by Bitcoin Core quorum signing.");
    }

    @Override
    public PaymentResult payLightning(LightningPaymentCommand command) {
        if (lndRestLightningClient == null) {
            throw new IllegalStateException("LND REST is required for outbound Lightning payments.");
        }
        LndRestLightningClient.LightningPaymentResult payment = lndRestLightningClient.payInvoice(
                command.paymentRequest(),
                command.amountSats(),
                command.maxFeeSats());
        return new PaymentResult(
                null,
                null,
                payment.paymentHash(),
                payment.status(),
                payment.feeSats(),
                payment.rawPayload());
    }

    public JsonNode loadInvoice(String invoiceId) {
        return get("/api/v1/stores/" + storeId + "/invoices/" + invoiceId);
    }

    private JsonNode findPaymentMethod(String invoiceId, String preferredPaymentMethodId) {
        JsonNode response = get(
                "/api/v1/stores/" + storeId + "/invoices/" + invoiceId + "/payment-methods?includeSensitive=true");
        if (response == null || !response.isArray()) {
            return objectMapper.createObjectNode();
        }
        for (JsonNode item : response) {
            String paymentMethodId = text(item, "paymentMethodId", "paymentMethod", "id");
            if (paymentMethodId != null && paymentMethodId.equalsIgnoreCase(preferredPaymentMethodId)) {
                return item;
            }
        }
        for (JsonNode item : response) {
            String paymentMethodId = text(item, "paymentMethodId", "paymentMethod", "id");
            if (paymentMethodId != null && paymentMethodId.toUpperCase().endsWith("-LN")) {
                return item;
            }
        }
        return response.size() > 0 ? response.get(0) : objectMapper.createObjectNode();
    }

    private JsonNode get(String path) {
        try {
            HttpEntity<Void> request = new HttpEntity<>(headers());
            ResponseEntity<String> response = restTemplate.exchange(baseUrl + path, HttpMethod.GET, request, String.class);
            return parse(response);
        } catch (Exception ex) {
            throw new IllegalStateException("BTCPay request failed on " + path, ex);
        }
    }

    private JsonNode post(String path, Map<String, ?> payload) {
        try {
            HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(payload), headers());
            ResponseEntity<String> response = restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
            return parse(response);
        } catch (Exception ex) {
            throw new IllegalStateException("BTCPay request failed on " + path, ex);
        }
    }

    private JsonNode parse(ResponseEntity<String> response) throws Exception {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("BTCPay returned HTTP " + response.getStatusCode());
        }
        return objectMapper.readTree(response.getBody());
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "token " + apiKey);
        return headers;
    }

    private String normalizeInvoiceStatus(JsonNode invoice) {
        String status = text(invoice, "status");
        if (status == null) {
            return "PENDING";
        }
        return switch (status.trim().toUpperCase()) {
            case "SETTLED" -> "SETTLED";
            case "EXPIRED" -> "EXPIRED";
            case "INVALID" -> "FAILED";
            case "PROCESSING" -> "PENDING";
            default -> "PENDING";
        };
    }

    private String mergePayload(JsonNode invoice, JsonNode paymentMethod) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoice", invoice);
        payload.put("paymentMethod", paymentMethod);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return invoice.toString();
        }
    }

    private String text(JsonNode node, String... fields) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
            JsonNode additionalData = node.path("additionalData");
            if (additionalData.isObject()) {
                JsonNode nested = additionalData.path(field);
                if (!nested.isMissingNode() && !nested.isNull()) {
                    String text = nested.asText();
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    private LocalDateTime parseDateTime(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value == null) {
                continue;
            }
            try {
                return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            } catch (Exception ignored) {
            }
            try {
                return LocalDateTime.parse(value);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private long btcNodeToSats(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return 0L;
        }
        try {
            BigDecimal btc = node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText("0"));
            return btc.multiply(SATOSHIS_PER_BITCOIN)
                    .setScale(0, RoundingMode.DOWN)
                    .longValue();
        } catch (Exception ex) {
            return 0L;
        }
    }

    private BigDecimal satsToBtc(long sats) {
        return new BigDecimal(sats).divide(SATOSHIS_PER_BITCOIN, 8, RoundingMode.HALF_UP);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String safeText(String value) {
        return value != null ? value : "";
    }

    private String sanitize(String value) {
        String trimmed = value != null ? value.trim() : "";
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
