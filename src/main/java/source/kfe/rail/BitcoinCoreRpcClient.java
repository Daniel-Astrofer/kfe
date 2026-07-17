package source.kfe.rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Primary
@Component("kfeBitcoinCoreRpcClient")
@ConditionalOnProperty(prefix = "bitcoin.rpc", name = "enabled", havingValue = "true")
public class BitcoinCoreRpcClient implements BlockchainClient {

    private static final Logger log = LoggerFactory.getLogger(BitcoinCoreRpcClient.class);
    private static final BigDecimal SATOSHIS_PER_BITCOIN = new BigDecimal("100000000");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final String walletName;

    public BitcoinCoreRpcClient(
            @Qualifier("bitcoindRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${bitcoin.rpc.url}") String baseUrl,
            @Value("${bitcoin.rpc.username}") String username,
            @Value("${bitcoin.rpc.password}") String password,
            @Value("${bitcoin.rpc.wallet:}") String walletName) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        
        this.baseUrl = sanitizeBaseUrl(baseUrl);
        
        this.username = username;
        this.password = password;
        
        this.walletName = sanitizeWalletName(walletName);
    }

    @Override
    public JsonNode executeRpc(String method, Object... params) {
        return executeRpcAt(resolveEndpoint(), method, params);
    }

    public JsonNode executeNodeRpc(String method, Object... params) {
        return executeRpcAt(baseUrl, method, params);
    }

    private JsonNode executeRpcAt(String endpoint, String method, Object... params) {
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "1.0");
            request.put("id", UUID.randomUUID().toString());
            request.put("method", method);
            ArrayNode array = request.putArray("params");
            if (params != null) {
                for (Object param : params) {
                    array.add(objectMapper.valueToTree(param));
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.AUTHORIZATION, basicAuthHeader());
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(endpoint, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Bitcoin Core RPC returned HTTP " + response.getStatusCode());
            }

            JsonNode body = objectMapper.readTree(response.getBody());
            JsonNode error = body.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new IllegalStateException(
                        "Bitcoin Core RPC " + method + " failed: " + error.path("message").asText("unknown error"));
            }
            return body;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Bitcoin Core RPC request failed for method "
                            + method
                            + ": "
                            + ex.getClass().getSimpleName()
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    @Override
    public String sendRawTransaction(String hex) {
        JsonNode result = unwrapResult(executeRpc("sendrawtransaction", hex));
        return result != null && !result.isNull() ? result.asText() : null;
    }

    @Override
    public JsonNode getRawTransaction(String txid, boolean verbose) {
        try {
            return unwrapResult(executeRpc("getrawtransaction", txid, verbose ? 1 : 0));
        } catch (RuntimeException rawTransactionFailure) {
            return walletTransaction(txid, rawTransactionFailure);
        }
    }

    public long getBlockCount() {
        JsonNode result = unwrapResult(executeRpc("getblockcount"));
        return result != null && result.isNumber() ? result.asLong() : 0L;
    }

    public String walletName() {
        return walletName;
    }

    public String chain() {
        return text(blockchainInfo(), "chain");
    }

    public JsonNode blockchainInfo() {
        return unwrapResult(executeNodeRpc("getblockchaininfo"));
    }

    public boolean isValidAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        JsonNode result = unwrapResult(executeNodeRpc("validateaddress", address.trim()));
        return result != null && result.path("isvalid").asBoolean(false);
    }

    public long estimateSmartFeeRateSatPerVbyte(int confirmationTarget) {
        if (confirmationTarget <= 0) {
            throw new IllegalArgumentException("confirmationTarget must be positive");
        }
        JsonNode result = unwrapResult(executeNodeRpc("estimatesmartfee", confirmationTarget, "CONSERVATIVE"));
        JsonNode feeRate = result != null ? result.path("feerate") : null;
        if (feeRate == null || feeRate.isMissingNode() || feeRate.isNull() || !feeRate.isNumber()) {
            throw new IllegalStateException("Bitcoin Core did not return a smart fee rate.");
        }
        long satPerVbyte = feeRate.decimalValue()
                .multiply(SATOSHIS_PER_BITCOIN)
                .divide(BigDecimal.valueOf(1000L), 0, RoundingMode.CEILING)
                .longValueExact();
        if (satPerVbyte <= 0L) {
            throw new IllegalStateException("Bitcoin Core returned a non-positive smart fee rate.");
        }
        return satPerVbyte;
    }

    public void ensureWalletLoaded(String wallet) {
        String cleanWallet = sanitizeWalletName(wallet);
        if (cleanWallet.isBlank()) {
            return;
        }
        if (listWallets().contains(cleanWallet)) {
            return;
        }
        if (listWalletDir().contains(cleanWallet)) {
            try {
                unwrapResult(executeNodeRpc("loadwallet", cleanWallet));
                return;
            } catch (RuntimeException loadFailure) {
                if (listWallets().contains(cleanWallet)) {
                    return;
                }
                throw loadFailure;
            }
        }
        unwrapResult(executeNodeRpc("createwallet", cleanWallet, false, false, "", false, true));
    }

    private List<String> listWallets() {
        JsonNode result = unwrapResult(executeNodeRpc("listwallets"));
        if (result == null || !result.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(
                result,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private List<String> listWalletDir() {
        JsonNode result = unwrapResult(executeNodeRpc("listwalletdir"));
        JsonNode wallets = result != null ? result.path("wallets") : null;
        if (wallets == null || !wallets.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(
                wallets.findValues("name"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    public String getNewAddress(String label) {
        JsonNode result = unwrapResult(executeRpc("getnewaddress", label != null ? label : "", "bech32"));
        String address = result != null && !result.isNull() ? result.asText() : "";
        if (address.isBlank()) {
            throw new IllegalStateException("Bitcoin Core did not return a new receiving address.");
        }
        return address;
    }

    private JsonNode walletTransaction(String txid, RuntimeException rawTransactionFailure) {
        try {
            return unwrapResult(executeRpc("gettransaction", txid, true, true));
        } catch (RuntimeException walletFailure) {
            rawTransactionFailure.addSuppressed(walletFailure);
            throw rawTransactionFailure;
        }
    }

    public FundedPsbt createFundedPsbt(String destinationAddress, long amountSats, Integer confirmationTarget) {
        return createFundedPsbt(destinationAddress, amountSats, confirmationTarget, null);
    }

    /**
     * Funds a custodial PSBT. Prefer explicit {@code feeRateSatsPerVbyte} (user-selected tier);
     * otherwise fall back to Bitcoin Core {@code conf_target}.
     */
    public FundedPsbt createFundedPsbt(
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put(destinationAddress, satsToBtc(amountSats));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("includeWatching", true);
        options.put("change_type", "bech32");
        boolean explicitFeeRate = feeRateSatsPerVbyte != null && feeRateSatsPerVbyte > 0L;
        if (explicitFeeRate) {
            // BTC/kvB — same units as watch-only path / Core walletcreatefundedpsbt.
            options.put("fee_rate", satsPerVbyteToBtcPerKvbyte(feeRateSatsPerVbyte));
        } else if (confirmationTarget != null && confirmationTarget > 0) {
            options.put("conf_target", confirmationTarget);
        }

        JsonNode result = unwrapResult(executeRpc(
                "walletcreatefundedpsbt",
                List.of(),
                List.of(output),
                0,
                options,
                true));

        String psbt = text(result, "psbt");
        long feeSats = btcNodeToSats(result.path("fee"));
        return new FundedPsbt(psbt, feeSats);
    }

    /**
     * Imports a watch-only output descriptor into the configured Core wallet so
     * {@code listunspent}/{@code listreceivedbyaddress} can see cold funds.
     * Also attempts the matching change branch when the descriptor ends with {@code /0/*}.
     */
    public void importWatchOnlyDescriptor(String descriptor, LocalDateTime timestamp) {
        if (descriptor == null || descriptor.isBlank()) {
            throw new IllegalArgumentException("descriptor is required");
        }
        String receive = withDescriptorChecksum(descriptor.trim());
        importDescriptorInternal(receive, timestamp);
        String change = toChangeDescriptor(receive);
        if (change != null && !change.equals(receive)) {
            try {
                importDescriptorInternal(withDescriptorChecksum(change), timestamp);
            } catch (RuntimeException exception) {
                // Receive import is enough for funding; change is best-effort for PSBT change detection.
            }
        }
    }

    private void importDescriptorInternal(String descriptor, LocalDateTime timestamp) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("desc", descriptor);
        if (timestamp != null) {
            request.put("timestamp", timestamp.toEpochSecond(java.time.ZoneOffset.UTC));
        } else {
            request.put("timestamp", "now");
        }
        // Cold watch-only: do NOT mark active (would conflict with hot wallet active
        // receive descriptors) and never set label on ranged descs (Core error -8).
        // Do not send "watchonly" — descriptor wallets reject / ignore it.
        request.put("active", false);
        request.put("internal", descriptor.contains("/1/*"));
        if (descriptor.contains("*")) {
            request.put("range", List.of(0, 1000));
        }
        // importdescriptors takes a single param: array of descriptor request objects.
        JsonNode result = unwrapResult(executeRpc("importdescriptors", List.of(request)));
        requireImportSuccess(result, descriptor);
    }

    private static void requireImportSuccess(JsonNode result, String descriptor) {
        if (result == null || !result.isArray() || result.isEmpty()) {
            throw new IllegalStateException(
                    "importdescriptors returned empty result for descriptor "
                            + abbreviateDescriptor(descriptor));
        }
        for (JsonNode item : result) {
            if (item != null && item.path("success").asBoolean(false)) {
                return;
            }
        }
        String error = result.get(0).path("error").path("message").asText("unknown error");
        throw new IllegalStateException(
                "importdescriptors failed for "
                        + abbreviateDescriptor(descriptor)
                        + ": "
                        + error);
    }

    private static String abbreviateDescriptor(String descriptor) {
        if (descriptor == null) {
            return "null";
        }
        String bare = descriptor.trim();
        return bare.length() <= 48 ? bare : bare.substring(0, 48) + "…";
    }

    private String withDescriptorChecksum(String descriptor) {
        String bare = descriptor;
        int hash = bare.indexOf('#');
        if (hash >= 0) {
            bare = bare.substring(0, hash);
        }
        // Node-level RPC — not wallet-scoped (wallet endpoint rejects / is flaky).
        JsonNode info = unwrapResult(executeNodeRpc("getdescriptorinfo", bare));
        String checksummed = text(info, "descriptor");
        if (checksummed == null || checksummed.isBlank()) {
            throw new IllegalStateException("Bitcoin Core getdescriptorinfo did not return a descriptor.");
        }
        return checksummed;
    }

    /**
     * scantxoutset is a node RPC. Calling it on /wallet/... fails on many Core builds
     * and surfaces as "RPC request failed for method scantxoutset".
     */
    @Override
    public long getConfirmedBalanceForDescriptor(String descriptor, int range) {
        if (descriptor == null || descriptor.isBlank()) {
            return 0L;
        }
        int safeRange = Math.max(1, range);
        Map<String, Object> scanObject = new LinkedHashMap<>();
        scanObject.put("desc", descriptor.trim());
        scanObject.put("range", safeRange);
        JsonNode result = startScantxoutset(scanObject);
        if (result == null || result.isNull() || result.isMissingNode()) {
            return 0L;
        }
        JsonNode totalAmount = result.path("total_amount");
        if (!totalAmount.isNumber()) {
            return 0L;
        }
        return btcNodeToSats(totalAmount);
    }

    @Override
    public List<AddressUtxo> getUnspentOutputsFromScan(String descriptorOrAddr, int range) {
        if (descriptorOrAddr == null || descriptorOrAddr.isBlank()) {
            return List.of();
        }
        String desc = descriptorOrAddr.trim();
        if (!desc.contains("(")) {
            desc = "addr(" + desc + ")";
        }
        // Propagate failures: empty list must mean "no UTXOs", not "scan aborted".
        // Callers that need soft-fail should catch RuntimeException themselves.
        Map<String, Object> scanObject = new LinkedHashMap<>();
        scanObject.put("desc", desc);
        if (range > 1) {
            scanObject.put("range", Math.max(1, range));
        }
        JsonNode result = startScantxoutset(scanObject);
        return parseNodeScantxoutsetUnspents(result);
    }

    @Override
    public long getBlockTipHeight() {
        return getBlockCount();
    }

    private List<AddressUtxo> parseNodeScantxoutsetUnspents(JsonNode result) {
        if (result == null || result.isNull() || result.isMissingNode()) {
            return List.of();
        }
        long tipHeight = result.path("height").isIntegralNumber() ? result.path("height").asLong() : 0L;
        JsonNode unspents = result.path("unspents");
        if (!unspents.isArray()) {
            return List.of();
        }
        java.util.ArrayList<AddressUtxo> results = new java.util.ArrayList<>();
        for (JsonNode utxo : unspents) {
            String txid = text(utxo, "txid");
            JsonNode vout = utxo.path("vout");
            long valueSats = btcNodeToSats(utxo.path("amount"));
            if (utxo.path("value").isIntegralNumber()) {
                valueSats = utxo.path("value").asLong();
            }
            if (txid == null || !vout.isIntegralNumber() || valueSats <= 0L) {
                continue;
            }
            int confs = 0;
            if (utxo.path("confirmations").isIntegralNumber()) {
                confs = Math.max(0, utxo.path("confirmations").asInt());
            } else if (utxo.path("height").isIntegralNumber() && tipHeight > 0L) {
                long h = utxo.path("height").asLong();
                if (h > 0L) {
                    confs = (int) Math.max(0L, tipHeight - h + 1L);
                }
            }
            String script = text(utxo, "scriptPubKey");
            results.add(new AddressUtxo(txid, vout.asInt(), valueSats, script, confs, null));
        }
        return List.copyOf(results);
    }

    /**
     * Core allows only one scantxoutset at a time. Serialize all starts in-process and
     * <em>wait</em> for the prior scan to finish. Aborting mid-scan was racing cold balance
     * probes (partial 0 vs full descriptor) and made observed_sats oscillate.
     */
    private JsonNode startScantxoutset(Map<String, Object> scanObject) {
        synchronized (SCANTXOUTSET_LOCK) {
            RuntimeException last = null;
            for (int attempt = 1; attempt <= 5; attempt++) {
                waitForScantxoutsetIdle(90_000L);
                try {
                    return unwrapResult(executeNodeRpc("scantxoutset", "start", List.of(scanObject)));
                } catch (RuntimeException error) {
                    last = error;
                    if (!isScanInProgress(error)) {
                        throw error;
                    }
                    log.warn(
                            "[BitcoinCore] scantxoutset still busy (attempt {}/5); waiting for idle",
                            attempt);
                    // Last resort only: if stuck past wait, abort once then retry.
                    if (attempt >= 4) {
                        try {
                            executeNodeRpc("scantxoutset", "abort");
                        } catch (RuntimeException abortError) {
                            log.debug("[BitcoinCore] scantxoutset abort: {}", abortError.getMessage());
                        }
                    }
                    sleepQuiet(500L * attempt);
                }
            }
            throw last != null ? last : new IllegalStateException("scantxoutset failed");
        }
    }

    private void waitForScantxoutsetIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(1_000L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            try {
                JsonNode status = unwrapResult(executeNodeRpc("scantxoutset", "status"));
                if (status == null || status.isNull() || status.isMissingNode()) {
                    return; // idle
                }
                // status object with progress means a scan is running
                if (!status.has("progress") && !status.isObject()) {
                    return;
                }
                if (status.isObject() && status.path("progress").isMissingNode()
                        && status.size() == 0) {
                    return;
                }
            } catch (RuntimeException ignored) {
                return; // treat RPC errors as idle enough to try start
            }
            sleepQuiet(400L);
        }
        log.warn("[BitcoinCore] scantxoutset still busy after {}ms wait", timeoutMs);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final Object SCANTXOUTSET_LOCK = new Object();

    private static boolean isScanInProgress(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("scan already in progress") || lower.contains("\"code\":-8");
    }

    @Override
    public long getConfirmedBalanceForAddress(String address) {
        if (address == null || address.isBlank()) {
            return 0L;
        }
        return getConfirmedBalanceForDescriptor("addr(" + address.trim() + ")", 1);
    }

    private static String toChangeDescriptor(String receiveDescriptor) {
        if (receiveDescriptor == null) {
            return null;
        }
        // Strip checksum before path rewrite; checksum re-applied by getdescriptorinfo.
        String bare = receiveDescriptor;
        int hash = bare.indexOf('#');
        if (hash >= 0) {
            bare = bare.substring(0, hash);
        }
        if (!bare.contains("/0/*")) {
            return null;
        }
        return bare.replace("/0/*", "/1/*");
    }

    public FundedPsbt createWatchOnlyPsbt(
            List<PsbtInput> selectedInputs,
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte) {
        return createWatchOnlyPsbt(
                selectedInputs,
                destinationAddress,
                amountSats,
                confirmationTarget,
                feeRateSatsPerVbyte,
                null);
    }

    /**
     * @param changeAddress optional cold-wallet change address. When set, Core must not
     *                      send change to the hot keypool (critical for watch-only spends).
     */
    public FundedPsbt createWatchOnlyPsbt(
            List<PsbtInput> selectedInputs,
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte,
            String changeAddress) {
        if (selectedInputs == null || selectedInputs.isEmpty()) {
            throw new IllegalArgumentException("At least one selected input is required for watch-only PSBT creation.");
        }
        if (destinationAddress == null || destinationAddress.isBlank()) {
            throw new IllegalArgumentException("destinationAddress is required for watch-only PSBT creation.");
        }
        List<Map<String, Object>> inputs = selectedInputs.stream()
                .map(input -> Map.<String, Object>of(
                        "txid", input.txid(),
                        "vout", input.vout()))
                .toList();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put(destinationAddress.trim(), satsToBtc(amountSats));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("includeWatching", true);
        options.put("add_inputs", false);
        if (changeAddress != null && !changeAddress.isBlank()) {
            options.put("changeAddress", changeAddress.trim());
        } else {
            // Fallback only — callers should always pass cold change for WATCH_ONLY.
            options.put("change_type", "bech32");
        }
        boolean explicitFeeRate = feeRateSatsPerVbyte != null && feeRateSatsPerVbyte > 0L;
        if (explicitFeeRate) {
            options.put("fee_rate", satsPerVbyteToBtcPerKvbyte(feeRateSatsPerVbyte));
        }
        if (!explicitFeeRate && confirmationTarget != null && confirmationTarget > 0) {
            options.put("conf_target", confirmationTarget);
        }

        JsonNode result = unwrapResult(executeRpc(
                "walletcreatefundedpsbt",
                inputs,
                List.of(output),
                0,
                options,
                true));

        String psbt = text(result, "psbt");
        long feeSats = btcNodeToSats(result.path("fee"));
        return new FundedPsbt(psbt, feeSats);
    }

    public JsonNode decodePsbt(String psbt) {
        return unwrapResult(executeRpc("decodepsbt", psbt));
    }

    public String combinePsbt(List<String> partialPsbts) {
        JsonNode result = unwrapResult(executeRpc("combinepsbt", partialPsbts));
        return result != null && !result.isNull() ? result.asText() : null;
    }

    public FinalizedPsbt finalizePsbt(String psbt) {
        JsonNode result = unwrapResult(executeRpc("finalizepsbt", psbt, true));
        return new FinalizedPsbt(
                text(result, "hex"),
                result.path("complete").asBoolean(false));
    }

    /**
     * Signs a PSBT with keys available in the loaded Core wallet ({@code walletprocesspsbt}).
     * Used as a first-class production signer node when custody keys live in Bitcoin Core
     * (or as one contributor in a multi-signer quorum).
     */
    public String walletProcessPsbt(String psbt) {
        if (psbt == null || psbt.isBlank()) {
            throw new IllegalArgumentException("psbt is required");
        }
        // Bitcoin Core 28: walletprocesspsbt "psbt" (sign sighashtype bip32derivs finalize)
        // finalize=false — leave finalization to the quorum assembler after combinepsbt
        JsonNode result = unwrapResult(executeRpc(
                "walletprocesspsbt",
                psbt.trim(),
                true,
                "ALL",
                true,
                false));
        String processed = text(result, "psbt");
        if (processed == null || processed.isBlank()) {
            throw new IllegalStateException("Bitcoin Core walletprocesspsbt did not return a PSBT.");
        }
        return processed;
    }

    /**
     * Confirmation count for a wallet-known or mempool/chain transaction.
     * Empty when the transaction is not found; {@code 0} means in mempool (unconfirmed).
     */
    public java.util.OptionalInt findTransactionConfirmations(String txid) {
        if (txid == null || txid.isBlank()) {
            return java.util.OptionalInt.empty();
        }
        String id = txid.trim();
        // Prefer wallet-aware gettransaction when available (always exposes confirmations).
        try {
            JsonNode walletTx = unwrapResult(executeRpc("gettransaction", id));
            if (walletTx != null && !walletTx.isNull() && !walletTx.isMissingNode()) {
                JsonNode confirmations = walletTx.path("confirmations");
                if (confirmations.isIntegralNumber()) {
                    return java.util.OptionalInt.of(Math.max(0, confirmations.asInt()));
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to getrawtransaction
        }
        try {
            JsonNode raw = getRawTransaction(id, true);
            if (raw == null || raw.isNull() || raw.isMissingNode()) {
                return java.util.OptionalInt.empty();
            }
            JsonNode confirmations = raw.path("confirmations");
            if (confirmations.isIntegralNumber()) {
                return java.util.OptionalInt.of(Math.max(0, confirmations.asInt()));
            }
            // Present in mempool/wallet without a confirmations field yet.
            return java.util.OptionalInt.of(0);
        } catch (RuntimeException ignored) {
            return java.util.OptionalInt.empty();
        }
    }

    /** @return confirmation count, or {@code -1} when not found */
    public int getTransactionConfirmations(String txid) {
        return findTransactionConfirmations(txid).orElse(-1);
    }

    public JsonNode decodeRawTransaction(String rawHex) {
        return unwrapResult(executeRpc("decoderawtransaction", rawHex));
    }

    private String resolveEndpoint() {
        if (walletName == null || walletName.isBlank()) {
            return baseUrl;
        }
        return baseUrl + "/wallet/" + walletName;
    }

    private String basicAuthHeader() {
        String token = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode unwrapResult(JsonNode response) {
        if (response != null && response.has("result")) {
            return response.get("result");
        }
        return response;
    }

    private long btcNodeToSats(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return 0L;
        }
        BigDecimal btc = value.isNumber()
                ? value.decimalValue()
                : new BigDecimal(value.asText("0"));
        return btc.multiply(SATOSHIS_PER_BITCOIN)
                .setScale(0, RoundingMode.DOWN)
                .longValue();
    }

    private BigDecimal satsPerVbyteToBtcPerKvbyte(long satsPerVbyte) {
        return BigDecimal.valueOf(satsPerVbyte)
                .multiply(BigDecimal.valueOf(1000L))
                .divide(SATOSHIS_PER_BITCOIN, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal satsToBtc(long sats) {
        return new BigDecimal(sats).divide(SATOSHIS_PER_BITCOIN, 8, RoundingMode.HALF_UP);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String sanitizeBaseUrl(String url) {
        String trimmed = url != null ? url.trim() : "";
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("bitcoin.rpc.url is required");
        }
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("bitcoin.rpc.url must use http or https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("bitcoin.rpc.url must include a host");
            }
            if (uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("bitcoin.rpc.url must not include userinfo credentials");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("bitcoin.rpc.url must not include query or fragment components");
            }
            String normalized = uri.normalize().toASCIIString();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("bitcoin.rpc.url must be a valid URI", exception);
        }
    }

    private String sanitizeWalletName(String walletName) {
        String cleanWallet = walletName != null ? walletName.trim() : "";
        if (cleanWallet.isEmpty()) {
            return "";
        }
        if (!cleanWallet.matches("^[A-Za-z0-9._-]{1,64}$") || ".".equals(cleanWallet) || "..".equals(cleanWallet)) {
            throw new IllegalArgumentException(
                    "bitcoin.rpc.wallet may only contain letters, numbers, dots, underscores, and hyphens");
        }
        return URLEncoder.encode(cleanWallet, StandardCharsets.UTF_8);
    }

    public record FundedPsbt(String psbt, long feeSats) {
    }

    public record FinalizedPsbt(String hex, boolean complete) {
    }

    public record PsbtInput(String txid, int vout) {
    }
}
