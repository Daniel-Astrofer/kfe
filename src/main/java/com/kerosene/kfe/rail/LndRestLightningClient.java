package com.kerosene.kfe.rail;

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

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LND REST adapter: balance probe, pay invoice, create/lookup invoices, channels.
 * Enabled only when {@code lightning.lnd.rest.enabled=true}.
 * Marked {@link Primary} so channel lifecycle prefers this bean when invoice/payment
 * gateway aliases also expose the same implementation type.
 */
@Component("kfeLndRestLightningClient")
@Primary
@ConditionalOnProperty(prefix = "lightning.lnd.rest", name = "enabled", havingValue = "true")
public class LndRestLightningClient
        implements LightningClient, LightningPaymentGateway, LightningInvoiceGateway, LightningChannelGateway {

    /** TLV type for keysend preimage (LND / BOLT). */
    private static final String KEYSEND_PREIMAGE_RECORD = "5482373484";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String macaroonHex;
    private final int paymentTimeoutSeconds;
    private final int defaultInvoiceExpirySeconds;
    private final LnurlPayResolver lnurlPayResolver;
    private final SecureRandom secureRandom = new SecureRandom();

    public LndRestLightningClient(
            @Qualifier("lndRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${lightning.lnd.base-url}") String baseUrl,
            @Value("${lightning.lnd.macaroon}") String macaroonHex,
            @Value("${lightning.lnd.payment-timeout-seconds:30}") int paymentTimeoutSeconds,
            @Value("${lightning.lnd.invoice-expiry-seconds:3600}") int defaultInvoiceExpirySeconds,
            org.springframework.beans.factory.ObjectProvider<LnurlPayResolver> lnurlPayResolver) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = sanitize(baseUrl);
        this.macaroonHex = macaroonHex != null ? macaroonHex.trim() : "";
        this.paymentTimeoutSeconds = paymentTimeoutSeconds;
        this.defaultInvoiceExpirySeconds = Math.max(60, defaultInvoiceExpirySeconds);
        this.lnurlPayResolver = lnurlPayResolver.getIfAvailable();
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
    public int pendingHtlcCount() {
        JsonNode channels = channelsArray();
        if (channels == null) {
            return -1;
        }
        int total = 0;
        for (JsonNode channel : channels) {
            JsonNode pending = channel.path("pending_htlcs");
            if (pending.isArray()) {
                total += pending.size();
            }
        }
        return total;
    }

    @Override
    public Set<String> peersWithPendingHtlcs() {
        JsonNode channels = channelsArray();
        if (channels == null) {
            return Set.of();
        }
        Set<String> peers = new HashSet<>();
        for (JsonNode channel : channels) {
            JsonNode pending = channel.path("pending_htlcs");
            if (!pending.isArray() || pending.isEmpty()) {
                continue;
            }
            String peer = text(channel, "remote_pubkey");
            if (peer != null && !peer.isBlank()) {
                peers.add(peer.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(peers);
    }

    private JsonNode channelsArray() {
        JsonNode response = get("/v1/channels");
        JsonNode channels = response.path("channels");
        if (!channels.isArray()) {
            return null;
        }
        return channels;
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
        requireLive();
        if (command.paymentRequest() == null || command.paymentRequest().isBlank()) {
            throw new IllegalArgumentException("Lightning destination (invoice / LNURL / address / pubkey) is required.");
        }
        LightningDestinationClassifier.Classified destination =
                LightningDestinationClassifier.classify(command.paymentRequest());
        if (destination == null) {
            throw new IllegalArgumentException(
                    "Invalid Lightning destination. Use BOLT11 (ln…), LNURL1…, user@domain, or 66-char node pubkey.");
        }

        LightningPaymentResult result = switch (destination.kind()) {
            case BOLT11 -> payInvoice(destination.value(), command.amountSats(), command.maxFeeSats());
            case KEYSEND -> payKeysend(destination.value(), command.amountSats(), command.maxFeeSats());
            case LNURL, LIGHTNING_ADDRESS -> {
                if (lnurlPayResolver == null) {
                    throw new IllegalStateException("LNURL / Lightning Address resolver is not available.");
                }
                String bolt11 = lnurlPayResolver.resolveBolt11(destination, command.amountSats());
                yield payInvoice(bolt11, command.amountSats(), command.maxFeeSats());
            }
        };

        LightningPaymentOutcome outcome = LightningPaymentOutcome.fromProviderStatus(result.status());
        return switch (outcome) {
            case SUCCEEDED -> new CustodyGateway.PaymentResult(
                    result.paymentHash(),
                    null,
                    result.paymentHash(),
                    outcome.name(),
                    result.feeSats(),
                    result.rawPayload());
            case FAILED -> throw new IllegalArgumentException(
                    "Lightning payment failed: " + nullToEmpty(result.status()));
            case IN_FLIGHT, UNKNOWN -> throw new LightningPaymentInFlightException(
                    "Lightning payment is not terminal (status=" + nullToEmpty(result.status()) + ").",
                    result.paymentHash(),
                    result.rawPayload());
        };
    }

    @Override
    public CustodyGateway.GeneratedLightningInvoice createLightningInvoice(
            CustodyGateway.LightningInvoiceCommand command) {
        requireLive();
        if (command.amountSats() <= 0L) {
            throw new IllegalArgumentException("Lightning invoice amountSats must be positive.");
        }
        int expiry = command.expiresInSeconds() > 0
                ? command.expiresInSeconds()
                : defaultInvoiceExpirySeconds;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("value", String.valueOf(command.amountSats()));
        payload.put("memo", command.memo() != null ? command.memo() : "KFE receive");
        payload.put("expiry", String.valueOf(expiry));
        payload.put("private", true);

        JsonNode response = post("/v1/invoices", payload);
        String paymentRequest = text(response, "payment_request");
        String rHashHex = paymentHashHex(response);
        LocalDateTime expiresAt = LocalDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(expiry);
        return new CustodyGateway.GeneratedLightningInvoice(
                paymentRequest,
                rHashHex,
                null,
                rHashHex,
                expiresAt);
    }

    @Override
    public CustodyGateway.IncomingLightningInvoiceStatus getLightningInvoiceStatus(
            CustodyGateway.LightningInvoiceStatusCommand command) {
        requireLive();
        String lookup = firstNonBlank(command.paymentHash(), command.providerReference());
        if (lookup == null) {
            throw new IllegalArgumentException("paymentHash is required for Lightning invoice status.");
        }
        String path = "/v1/invoice/" + encodePaymentHashPath(lookup);
        JsonNode response = get(path);
        if (response == null || response.isEmpty()) {
            return new CustodyGateway.IncomingLightningInvoiceStatus(
                    "UNKNOWN", null, null, "{}");
        }
        boolean settled = response.path("settled").asBoolean(false)
                || "SETTLED".equalsIgnoreCase(text(response, "state"));
        long received = longField(response, "amt_paid_sat", "value");
        if (received <= 0L) {
            received = nestedLong(response, "amt_paid", "sat", "msat") / 1000L;
        }
        String status = settled ? "SETTLED" : text(response, "state");
        if (status == null || status.isBlank()) {
            status = settled ? "SETTLED" : "OPEN";
        }
        return new CustodyGateway.IncomingLightningInvoiceStatus(
                status.toUpperCase(Locale.ROOT),
                received > 0L ? received : null,
                settled ? LocalDateTime.now(java.time.ZoneOffset.UTC) : null,
                response.toString());
    }

    @Override
    public boolean cancelLightningInvoice(CustodyGateway.LightningInvoiceCancellationCommand command) {
        // LND does not support true cancel of open invoices via a simple REST call in all versions.
        return false;
    }

    @Override
    public List<ChannelSnapshot> listChannels() {
        JsonNode channels = channelsArray();
        if (channels == null) {
            return List.of();
        }
        List<ChannelSnapshot> snapshots = new ArrayList<>();
        for (JsonNode channel : channels) {
            String chanId = text(channel, "chan_id");
            if (chanId == null && channel.path("chan_id").isIntegralNumber()) {
                chanId = String.valueOf(channel.path("chan_id").asLong());
            }
            snapshots.add(new ChannelSnapshot(
                    text(channel, "channel_point"),
                    text(channel, "remote_pubkey"),
                    channel.path("active").asBoolean(false),
                    longField(channel, "capacity"),
                    longField(channel, "local_balance"),
                    longField(channel, "remote_balance"),
                    channel.path("pending_htlcs").isArray() ? channel.path("pending_htlcs").size() : 0,
                    channel.path("initiator").asBoolean(false),
                    longField(channel, "commit_fee"),
                    chanId));
        }
        return List.copyOf(snapshots);
    }

    @Override
    public List<PendingChannelSnapshot> listPendingChannels() {
        requireLive();
        JsonNode response = get("/v1/channels/pending");
        List<PendingChannelSnapshot> out = new ArrayList<>();
        appendPending(out, response.path("pending_open_channels"), "PENDING_OPEN");
        appendPending(out, response.path("pending_force_closing_channels"), "PENDING_FORCE_CLOSE");
        appendPending(out, response.path("waiting_close_channels"), "WAITING_CLOSE");
        appendPending(out, response.path("pending_closing_channels"), "PENDING_CLOSING");
        return List.copyOf(out);
    }

    private void appendPending(
            List<PendingChannelSnapshot> out, JsonNode arr, String status) {
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode item : arr) {
            JsonNode channel = item.path("channel");
            if (channel.isMissingNode() || channel.isNull()) {
                channel = item;
            }
            String remote = text(channel, "remote_node_pub");
            if (remote == null || remote.isBlank()) {
                remote = text(channel, "remote_pubkey");
            }
            String point = text(channel, "channel_point");
            long capacity = longField(channel, "capacity");
            out.add(new PendingChannelSnapshot(remote, point, status, capacity));
        }
    }

    @Override
    public String newOnchainAddress(String label) {
        requireLive();
        Map<String, Object> payload = new LinkedHashMap<>();
        // WITNESS_PUBKEY_HASH (p2wkh) — standard LND wallet receive for funding.
        payload.put("type", 0);
        JsonNode response = post("/v1/newaddress", payload);
        String address = text(response, "address");
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("LND newaddress returned empty address.");
        }
        return address.trim();
    }

    @Override
    public long confirmedOnchainBalanceSats() {
        requireLive();
        JsonNode response = get("/v1/balance/blockchain");
        long confirmed = nestedLong(response, "confirmed_balance", "sat", "value");
        if (confirmed > 0L) {
            return confirmed;
        }
        return longField(response, "confirmed_balance");
    }

    @Override
    public OpenChannelResult openChannel(OpenChannelCommand command) {
        requireLive();
        if (command.peerPubkey() == null || command.peerPubkey().isBlank()) {
            throw new IllegalArgumentException("peerPubkey is required to open a channel.");
        }
        if (command.localAmountSats() <= 0L) {
            throw new IllegalArgumentException("localAmountSats must be positive.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("node_pubkey_string", command.peerPubkey().trim());
        payload.put("local_funding_amount", String.valueOf(command.localAmountSats()));
        payload.put("private", command.privateChannel());
        payload.put("min_confs", command.minConfsZero() ? 0 : 1);
        payload.put("spend_unconfirmed", command.minConfsZero());
        JsonNode response = post("/v1/channels", payload);
        String fundingTxid = text(response, "funding_txid_str");
        if (fundingTxid == null) {
            fundingTxid = text(response.path("funding_txid_bytes"), "funding_txid_str");
        }
        String outputIndex = text(response, "output_index");
        if (outputIndex == null && response.path("output_index").isIntegralNumber()) {
            outputIndex = String.valueOf(response.path("output_index").asLong());
        }
        String channelPoint = fundingTxid != null && outputIndex != null
                ? fundingTxid + ":" + outputIndex
                : fundingTxid;
        return new OpenChannelResult(fundingTxid, outputIndex, channelPoint, response.toString());
    }

    @Override
    public CloseChannelResult closeChannel(CloseChannelCommand command) {
        requireLive();
        if (command.channelPoint() == null || command.channelPoint().isBlank()) {
            throw new IllegalArgumentException("channelPoint is required to close a channel.");
        }
        String[] parts = command.channelPoint().trim().split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("channelPoint must be fundingTxid:outputIndex.");
        }
        String path = "/v1/channels/" + parts[0] + "/" + parts[1]
                + "?force=" + command.force();
        JsonNode response = delete(path);
        String closingTxid = text(response, "closing_txid");
        if (closingTxid == null) {
            closingTxid = text(response.path("close_pending"), "txid");
        }
        return new CloseChannelResult(closingTxid, response.toString());
    }

    @Override
    public CircularRebalanceResult attemptCircularRebalance(CircularRebalanceCommand command) {
        requireLive();
        if (command.amountSats() <= 0L) {
            return CircularRebalanceResult.failed("INVALID", null, "amountSats must be positive");
        }
        List<ChannelSnapshot> channels = listChannels();
        ChannelSnapshot target = channels.stream()
                .filter(c -> command.targetChannelPoint() != null
                        && command.targetChannelPoint().equals(c.channelPoint()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return CircularRebalanceResult.failed("NO_CHANNEL", null, "Target channel not found");
        }
        // Prefer outgoing hop = richest local channel that is not the drained target.
        List<String> outgoing = channels.stream()
                .filter(ChannelSnapshot::active)
                .filter(c -> c.chanId() != null && !c.chanId().isBlank())
                .filter(c -> !c.channelPoint().equals(target.channelPoint()))
                .filter(c -> c.localBalanceSats() > command.amountSats())
                .sorted((a, b) -> Long.compare(b.localBalanceSats(), a.localBalanceSats()))
                .map(ChannelSnapshot::chanId)
                .limit(3)
                .toList();
        if (outgoing.isEmpty()) {
            return CircularRebalanceResult.failed(
                    "NO_OUTGOING", null, "No alternate channel with enough local balance for circular pay");
        }

        CustodyGateway.GeneratedLightningInvoice invoice = createLightningInvoice(
                new CustodyGateway.LightningInvoiceCommand(
                        0L,
                        null,
                        "rebalance",
                        command.amountSats(),
                        command.memo() != null ? command.memo() : "KFE circular rebalance",
                        600));
        if (invoice.paymentRequest() == null || invoice.paymentRequest().isBlank()) {
            return CircularRebalanceResult.failed("INVOICE_FAILED", null, "Could not create self-invoice");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_request", invoice.paymentRequest());
        payload.put("timeout_seconds", paymentTimeoutSeconds);
        payload.put("fee_limit_sat", String.valueOf(Math.max(0L, command.maxFeeSats())));
        payload.put("allow_self_payment", true);
        payload.put("no_inflight_updates", true);
        // Force first hop away from drained channel so return path can refill it.
        payload.put("outgoing_chan_ids", outgoing.stream().map(id -> {
            try {
                return Long.parseLong(id);
            } catch (NumberFormatException ex) {
                return id;
            }
        }).toList());

        try {
            JsonNode response = post("/v2/router/send", payload);
            String status = text(response, "status");
            if (status == null && response.has("failure")) {
                status = "FAILED";
            }
            LightningPaymentOutcome outcome = LightningPaymentOutcome.fromProviderStatus(status);
            if (outcome == LightningPaymentOutcome.SUCCEEDED) {
                return CircularRebalanceResult.ok(
                        paymentHashHex(response),
                        longField(response, "fee_sat", "fee"),
                        response.toString());
            }
            if (outcome == LightningPaymentOutcome.IN_FLIGHT || outcome == LightningPaymentOutcome.UNKNOWN) {
                return CircularRebalanceResult.failed(
                        status != null ? status : "IN_FLIGHT",
                        response.toString(),
                        "Circular payment not terminal");
            }
            return CircularRebalanceResult.failed(
                    status != null ? status : "FAILED",
                    response.toString(),
                    "Circular payment failed");
        } catch (RuntimeException ex) {
            return CircularRebalanceResult.failed(
                    "ERROR", null, ex.getMessage() != null ? ex.getMessage() : "send failed");
        }
    }

    @Override
    public UpdatePolicyResult updateChannelPolicy(UpdatePolicyCommand command) {
        requireLive();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("base_fee_msat", String.valueOf(Math.max(0L, command.baseFeeMsat())));
        payload.put("fee_rate_ppm", String.valueOf(Math.max(0L, command.feeRatePpm())));
        payload.put("time_lock_delta", Math.max(18, command.timeLockDelta()));
        if (command.channelPoint() != null && !command.channelPoint().isBlank()) {
            String[] parts = command.channelPoint().trim().split(":");
            if (parts.length == 2) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("funding_txid_str", parts[0]);
                point.put("output_index", Integer.parseInt(parts[1]));
                payload.put("chan_point", point);
            }
        }
        JsonNode response = post("/v1/chanpolicy", payload);
        boolean failed = response.path("failed_updates").isArray()
                && !response.path("failed_updates").isEmpty();
        return new UpdatePolicyResult(!failed, response.toString());
    }

    /**
     * Spontaneous (keysend) payment to a node pubkey via {@code /v2/router/send}.
     */
    public LightningPaymentResult payKeysend(String nodePubkeyHex, long amountSats, long maxFeeSats) {
        if (nodePubkeyHex == null || !nodePubkeyHex.matches("(?i)[0-9a-f]{66}")) {
            throw new IllegalArgumentException("Keysend destination must be a 66-char hex node pubkey.");
        }
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("amountSats must be positive for keysend.");
        }
        byte[] preimage = new byte[32];
        secureRandom.nextBytes(preimage);
        byte[] paymentHash;
        try {
            paymentHash = MessageDigest.getInstance("SHA-256").digest(preimage);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not hash keysend preimage.", ex);
        }
        String paymentHashHex = HexFormat.of().formatHex(paymentHash);
        String preimageB64 = Base64.getEncoder().encodeToString(preimage);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dest", nodePubkeyHex.toLowerCase(Locale.ROOT));
        payload.put("amt", String.valueOf(amountSats));
        payload.put("timeout_seconds", paymentTimeoutSeconds);
        payload.put("fee_limit_sat", String.valueOf(Math.max(0L, maxFeeSats)));
        payload.put("no_inflight_updates", true);
        payload.put("payment_hash", Base64.getEncoder().encodeToString(paymentHash));
        Map<String, String> customRecords = new LinkedHashMap<>();
        customRecords.put(KEYSEND_PREIMAGE_RECORD, preimageB64);
        payload.put("dest_custom_records", customRecords);

        JsonNode response = post("/v2/router/send", payload);
        String status = text(response, "status");
        if (status == null && response.has("failure")) {
            status = "FAILED";
        }
        LightningPaymentOutcome outcome = LightningPaymentOutcome.fromProviderStatus(status);
        if (outcome == LightningPaymentOutcome.FAILED) {
            String failureReason = firstNonBlank(
                    text(response.path("failure"), "failure_reason"),
                    text(response, "failure_reason"),
                    text(response, "payment_error"),
                    status);
            throw new IllegalArgumentException(
                    "Lightning keysend failed: " + nullToEmpty(failureReason));
        }
        String hash = paymentHashHex(response);
        if (hash == null || hash.isBlank()) {
            hash = paymentHashHex;
        }
        return new LightningPaymentResult(hash, longField(response, "fee_sat", "fee"), status, response.toString());
    }

    public LightningPaymentResult payInvoice(String paymentRequest, long amountSats, long maxFeeSats) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_request", paymentRequest);
        payload.put("timeout_seconds", paymentTimeoutSeconds);
        payload.put("fee_limit_sat", String.valueOf(Math.max(0L, maxFeeSats)));
        payload.put("no_inflight_updates", true);
        // Local / same-node invoices (payment links paid on our LND) require this.
        payload.put("allow_self_payment", true);
        // LND rejects amt on fixed-amount invoices:
        // "amount must not be specified when paying a non-zero amount invoice".
        // Only send amt for zero-amount (amountless) invoices.
        long invoiceSats = decodeInvoiceAmountSats(paymentRequest);
        if (invoiceSats <= 0L) {
            if (amountSats <= 0L) {
                throw new IllegalArgumentException(
                        "Lightning amountSats is required when paying a zero-amount invoice.");
            }
            payload.put("amt", String.valueOf(amountSats));
        } else if (amountSats > 0L && amountSats != invoiceSats) {
            throw new IllegalArgumentException(
                    "Lightning amountSats (" + amountSats + ") does not match invoice amount (" + invoiceSats + ").");
        }

        JsonNode response = post("/v2/router/send", payload);
        String status = text(response, "status");
        if (status == null && response.has("failure")) {
            status = "FAILED";
        }
        // Surface permanent payment failures as non-retryable so the outbox does not
        // leave the user-facing tx stuck in EXECUTING/pending forever.
        LightningPaymentOutcome outcome = LightningPaymentOutcome.fromProviderStatus(status);
        if (outcome == LightningPaymentOutcome.FAILED) {
            String failureReason = firstNonBlank(
                    text(response.path("failure"), "failure_reason"),
                    text(response, "failure_reason"),
                    text(response, "payment_error"),
                    status);
            throw new IllegalArgumentException(
                    "Lightning payment failed: " + nullToEmpty(failureReason));
        }
        return new LightningPaymentResult(
                paymentHashHex(response),
                longField(response, "fee_sat", "fee"),
                status,
                response.toString());
    }

    /**
     * Invoice amount in sats: LND decode first, bolt11 HRP fallback.
     * Returns 0 only for true amountless invoices (or unparseable bech32 with no HRP amount).
     */
    private long decodeInvoiceAmountSats(String paymentRequest) {
        if (paymentRequest == null || paymentRequest.isBlank()) {
            return 0L;
        }
        try {
            String encoded = java.net.URLEncoder
                    .encode(paymentRequest.trim(), java.nio.charset.StandardCharsets.UTF_8);
            JsonNode decoded = get("/v1/payreq/" + encoded);
            long sats = longField(decoded, "num_satoshis", "num_satoshi");
            if (sats > 0L) {
                return sats;
            }
            long msat = longField(decoded, "num_msat", "num_msats");
            if (msat > 0L) {
                return msat / 1000L;
            }
            // Successful decode of amountless invoice → 0 (do not send amt unless caller requires).
            if (!decoded.path("payment_hash").isMissingNode()
                    || !decoded.path("destination").isMissingNode()
                    || !decoded.path("description").isMissingNode()) {
                return 0L;
            }
        } catch (RuntimeException ignored) {
            // offline HRP parse below
        }
        return parseBolt11AmountSatsFromHrp(paymentRequest.trim());
    }

    /**
     * Offline BOLT11 amount from human-readable part, e.g. {@code lntb1m1...} → 100_000 sats,
     * {@code lntb800u1...} → 80_000, {@code lnbc1p...} (no amount) → 0.
     */
    public static long parseBolt11AmountSatsFromHrp(String paymentRequest) {
        if (paymentRequest == null || paymentRequest.isBlank()) {
            return 0L;
        }
        String lower = paymentRequest.trim().toLowerCase(Locale.ROOT);
        // Bech32/BOLT11 separator is the *last* '1' before the data part.
        // Using indexOf would break amounts that contain '1' (e.g. lntb1m1...).
        int sep = lower.lastIndexOf('1');
        if (sep <= 0) {
            return 0L;
        }
        String hrp = lower.substring(0, sep);
        // strip network prefix: ln + (bc|tb|tbs|bcrt|sb)
        String rest = hrp;
        if (rest.startsWith("ln")) {
            rest = rest.substring(2);
        }
        for (String net : List.of("bcrt", "tbs", "bc", "tb", "sb")) {
            if (rest.startsWith(net)) {
                rest = rest.substring(net.length());
                break;
            }
        }
        if (rest.isEmpty()) {
            return 0L; // amountless
        }
        char multiplier = rest.charAt(rest.length() - 1);
        String numberPart;
        double multSats;
        if (multiplier >= '0' && multiplier <= '9') {
            numberPart = rest;
            multSats = 100_000_000d; // whole BTC
        } else {
            numberPart = rest.substring(0, rest.length() - 1);
            multSats = switch (multiplier) {
                case 'm' -> 100_000d;          // milli-btc
                case 'u' -> 100d;              // micro-btc
                case 'n' -> 0.1d;              // nano-btc
                case 'p' -> 0.0001d;           // pico-btc
                default -> -1d;
            };
            if (multSats < 0d) {
                return 0L;
            }
        }
        if (numberPart.isEmpty()) {
            return 0L;
        }
        try {
            double btcUnits = Double.parseDouble(numberPart);
            long sats = Math.round(btcUnits * multSats);
            return Math.max(0L, sats);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private void requireLive() {
        if (!isLive()) {
            throw new IllegalStateException("LND REST is not configured (base-url/macaroon).");
        }
    }

    private JsonNode get(String path) {
        try {
            HttpEntity<Void> request = new HttpEntity<>(headers());
            ResponseEntity<String> response =
                    restTemplate.exchange(baseUrl + path, HttpMethod.GET, request, String.class);
            return parse(response);
        } catch (Exception ex) {
            // Callers such as invoice status treat empty JSON as UNKNOWN; log so silent
            // lookup failures (wrong path encoding, TLS, 4xx/5xx) are visible in ops.
            org.slf4j.LoggerFactory.getLogger(LndRestLightningClient.class)
                    .warn("[LND REST] GET {} failed: {}", path, ex.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode post(String path, Map<String, ?> payload) {
        try {
            HttpEntity<String> request =
                    new HttpEntity<>(objectMapper.writeValueAsString(payload), headers());
            ResponseEntity<String> response =
                    restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
            return parse(response);
        } catch (org.springframework.web.client.HttpStatusCodeException httpEx) {
            String body = httpEx.getResponseBodyAsString();
            String detail = body != null && !body.isBlank() ? body : httpEx.getStatusText();
            String message = "LND REST request failed on " + path + ": " + detail;
            // Permanent payment/invoice errors must not be retried as EXECUTING forever.
            if (isPermanentLightningClientError(detail)) {
                throw new IllegalArgumentException(message, httpEx);
            }
            throw new IllegalStateException(message, httpEx);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            String message = "LND REST request failed on " + path
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            if (isPermanentLightningClientError(message)) {
                throw new IllegalArgumentException(message, ex);
            }
            throw new IllegalStateException(message, ex);
        }
    }

    /** Invoice expired / wrong amt / invalid payreq / auth failure — do not keep outbox retrying. */
    public static boolean isPermanentLightningClientError(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        return lower.contains("invoice expired")
                || lower.contains("invoice is already paid")
                || lower.contains("invoice already paid")
                || lower.contains("amount must not be specified")
                || lower.contains("amount must be specified")
                || lower.contains("invalid payment request")
                || lower.contains("checksum failed")
                || lower.contains("payment is in terminal state")
                || lower.contains("payment hash already exists")
                || lower.contains("destination unknown")
                || lower.contains("unable to find a path")
                || lower.contains("no route")
                || lower.contains("incorrect_or_unknown_payment_details")
                || lower.contains("incorrect payment details")
                || lower.contains("self-payments not allowed")
                || lower.contains("self payment not allowed")
                // Authentication/secrets errors — no retry possible without redeploy.
                || lower.contains("encoding/hex: invalid byte")
                || lower.contains("cannot set both feerate and feerateppm")
                || lower.contains("permission denied")
                || lower.contains("invalid macaroon");
    }

    private JsonNode delete(String path) {
        try {
            HttpEntity<Void> request = new HttpEntity<>(headers());
            ResponseEntity<String> response =
                    restTemplate.exchange(baseUrl + path, HttpMethod.DELETE, request, String.class);
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

    private String paymentHashHex(JsonNode response) {
        String direct = text(response, "payment_hash");
        if (direct != null && !direct.isBlank()) {
            if (looksLikeHex(direct)) {
                return direct.toLowerCase(Locale.ROOT);
            }
            try {
                return HexFormat.of().formatHex(Base64.getDecoder().decode(direct)).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException ignored) {
                return direct;
            }
        }
        JsonNode rHash = response.path("r_hash");
        if (rHash.isTextual()) {
            String value = rHash.asText();
            if (looksLikeHex(value)) {
                return value.toLowerCase(Locale.ROOT);
            }
            try {
                return HexFormat.of().formatHex(Base64.getDecoder().decode(value)).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException ignored) {
                return value;
            }
        }
        if (rHash.isBinary()) {
            try {
                return HexFormat.of().formatHex(rHash.binaryValue()).toLowerCase(Locale.ROOT);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * LND REST {@code GET /v1/invoice/{r_hash_str}} expects the payment hash as a
     * <strong>hex</strong> string (not base64). Sending base64url makes LND return
     * {@code encoding/hex: invalid byte} / HTTP 500, and the silent GET fallback
     * then treats the invoice as UNKNOWN — so settled invoices never credit.
     */
    private String encodePaymentHashPath(String paymentHash) {
        String trimmed = paymentHash.trim();
        if (looksLikeHex(trimmed)) {
            String normalized = trimmed.length() % 2 == 0 ? trimmed : "0" + trimmed;
            return normalized.toLowerCase(Locale.ROOT);
        }
        // Accept base64 / base64url inputs and normalize to hex for the path.
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(paddedBase64(trimmed));
            return HexFormat.of().formatHex(bytes);
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(paddedBase64(trimmed));
            return HexFormat.of().formatHex(bytes);
        } catch (IllegalArgumentException ignored) {
            return trimmed;
        }
    }

    private static String paddedBase64(String value) {
        String s = value.replace('-', '+').replace('_', '/');
        int mod = s.length() % 4;
        if (mod == 0) {
            return s;
        }
        return s + "====".substring(mod);
    }

    private boolean looksLikeHex(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private String sanitize(String value) {
        String trimmed = value != null ? value.trim() : "";
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    public record LightningPaymentResult(
            String paymentHash,
            long feeSats,
            String status,
            String rawPayload) {
    }
}
