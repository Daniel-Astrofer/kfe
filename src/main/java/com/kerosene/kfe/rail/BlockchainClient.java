package com.kerosene.kfe.rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.kerosene.common.validation.FinancialAmountValidator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface BlockchainClient {
    record FeeRates(long fastSatPerVByte, long halfHourSatPerVByte, long hourSatPerVByte) {
        public FeeRates {
            fastSatPerVByte = Math.max(1L, fastSatPerVByte);
            halfHourSatPerVByte = Math.max(1L, halfHourSatPerVByte);
            hourSatPerVByte = Math.max(1L, hourSatPerVByte);
        }
    }

    /**
     * Unspent outpoint. {@code confirmations} is 0 for mempool / unknown height;
     * {@code address} may be null when derived only from a descriptor scan.
     */
    record AddressUtxo(
            String txid,
            int vout,
            long valueSats,
            String scriptPubKey,
            int confirmations,
            String address) {
        public AddressUtxo(String txid, int vout, long valueSats, String scriptPubKey) {
            this(txid, vout, valueSats, scriptPubKey, 0, null);
        }
    }

    JsonNode executeRpc(String method, Object... params);

    String sendRawTransaction(String hex);

    JsonNode getRawTransaction(String txid, boolean verbose);

    default long getHotWalletBalance() {
        try {
            JsonNode balances = unwrapResult(executeRpc("getbalances"));
            long balance = parseBtcBalanceToSats(balances);
            if (balance > 0L) {
                return balance;
            }

            JsonNode legacyBalance = unwrapResult(executeRpc("getbalance"));
            return parseBtcBalanceToSats(legacyBalance);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    default FeeRates estimateSmartFee(int fastBlocks, int halfHourBlocks, int hourBlocks) {
        return new FeeRates(
                estimateSmartFeeForTarget(fastBlocks, 50L),
                estimateSmartFeeForTarget(halfHourBlocks, 25L),
                estimateSmartFeeForTarget(hourBlocks, 10L));
    }

    default JsonNode getAddressTransactions(String address) {
        if (address == null || address.isBlank()) {
            return JsonNodeFactory.instance.arrayNode();
        }

        try {
            JsonNode txs = unwrapResult(executeRpc("listreceivedbyaddress", 0, true, true, address));
            return txs != null && txs.isArray() ? txs : JsonNodeFactory.instance.arrayNode();
        } catch (RuntimeException e) {
            return JsonNodeFactory.instance.arrayNode();
        }
    }

    default long getConfirmedBalanceForAddress(String address) {
        if (address == null || address.isBlank()) {
            return 0L;
        }

        try {
            return scanDescriptorBalance("addr(" + address + ")", 1);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    default long getConfirmedBalanceForXpub(String xpub, int range, boolean includeChangeBranch) {
        if (xpub == null || xpub.isBlank()) {
            return 0L;
        }

        int safeRange = Math.max(1, range);
        long total = scanDescriptorBalance("wpkh(" + xpub + "/0/*)", safeRange);
        if (includeChangeBranch) {
            total += scanDescriptorBalance("wpkh(" + xpub + "/1/*)", safeRange);
        }
        return total;
    }

    /**
     * Confirmed UTXO-set balance for an output descriptor (e.g. {@code wpkh(xpub/0/*)}).
     * Uses {@code scantxoutset} — independent of wallet-internal ledger.
     */
    default long getConfirmedBalanceForDescriptor(String descriptor, int range) {
        if (descriptor == null || descriptor.isBlank()) {
            return 0L;
        }
        return scanDescriptorBalance(descriptor.trim(), Math.max(1, range));
    }

    /**
     * Sum of unspent outputs for specific addresses (includes 0-conf when Core reports them).
     * Always filter by address set — never sum the entire shared Core wallet.
     */
    default long getUnspentBalanceForAddresses(List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (String address : addresses) {
            if (address == null || address.isBlank()) {
                continue;
            }
            for (AddressUtxo utxo : getUnspentOutputs(address.trim())) {
                total = Math.addExact(total, utxo.valueSats());
            }
        }
        return total;
    }

    default List<AddressUtxo> getUnspentOutputs(String address) {
        if (address == null || address.isBlank()) {
            return List.of();
        }

        try {
            JsonNode utxos = unwrapResult(executeRpc("listunspent", 0, 9999999, List.of(address)));
            if (utxos == null || !utxos.isArray()) {
                return List.of();
            }

            List<AddressUtxo> results = new ArrayList<>();
            for (JsonNode utxo : utxos) {
                String txid = textField(utxo, "txid");
                JsonNode vout = utxo.path("vout");
                long valueSats = parseUtxoValueSats(utxo);
                if (txid != null && vout.isIntegralNumber() && valueSats > 0L) {
                    int confs = utxo.path("confirmations").isIntegralNumber()
                            ? Math.max(0, utxo.path("confirmations").asInt())
                            : 0;
                    results.add(new AddressUtxo(
                            txid,
                            vout.asInt(),
                            valueSats,
                            textField(utxo, "scriptPubKey"),
                            confs,
                            address.trim()));
                }
            }
            return results;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Unspents from the UTXO set via {@code scantxoutset} — works without Core wallet import.
     * Prefer this for watch-only / cold addresses.
     */
    default List<AddressUtxo> getUnspentOutputsFromScan(String descriptorOrAddr, int range) {
        if (descriptorOrAddr == null || descriptorOrAddr.isBlank()) {
            return List.of();
        }
        String desc = descriptorOrAddr.trim();
        if (!desc.contains("(")) {
            desc = "addr(" + desc + ")";
        }
        try {
            Map<String, Object> scanObject = new LinkedHashMap<>();
            scanObject.put("desc", desc);
            if (range > 1) {
                scanObject.put("range", range);
            }
            JsonNode result = unwrapResult(executeRpc("scantxoutset", "start", List.of(scanObject)));
            return parseScantxoutsetUnspents(result, null);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    default long getBlockTipHeight() {
        try {
            JsonNode height = unwrapResult(executeRpc("getblockcount"));
            return height != null && height.isNumber() ? Math.max(0L, height.asLong()) : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * True if the outpoint is still unspent considering the <em>mempool</em>
     * ({@code gettxout} with {@code include_mempool=true}).
     * {@code scantxoutset} ignores mempool spends — Electrum does not.
     *
     * <p>Important: when spent, Core returns JSON {@code "result": null}. Some unwrap
     * helpers return the whole RPC envelope in that case — we must treat that as spent.
     */
    default boolean isOutpointUnspentIncludingMempool(String txid, int vout) {
        if (txid == null || txid.isBlank() || vout < 0) {
            return false;
        }
        try {
            JsonNode raw = executeRpc("gettxout", txid.trim(), vout, true);
            if (raw == null || raw.isNull() || raw.isMissingNode()) {
                return false;
            }
            // Full RPC envelope: { "result": null|object, "error": ... }
            if (raw.has("result") || raw.has("error")) {
                JsonNode result = raw.get("result");
                if (result == null || result.isNull() || result.isMissingNode()) {
                    return false; // spent (or unknown → treat as spent for Electrum parity)
                }
                return result.has("value") || result.has("confirmations") || result.has("bestblock");
            }
            // Already-unwrapped UTXO object
            return raw.has("value") || raw.has("confirmations") || raw.has("bestblock");
        } catch (RuntimeException e) {
            return true; // fail open to avoid zeroing balance on RPC blip
        }
    }

    /**
     * Mempool (or chain) txid that spends the given outpoint, if known.
     * Uses Bitcoin Core {@code gettxspendingprevout} when available.
     */
    default String findSpendingTxid(String txid, int vout) {
        if (txid == null || txid.isBlank() || vout < 0) {
            return null;
        }
        try {
            Map<String, Object> outpoint = new LinkedHashMap<>();
            outpoint.put("txid", txid.trim());
            outpoint.put("vout", vout);
            JsonNode result = unwrapResult(executeRpc("gettxspendingprevout", List.of(outpoint)));
            if (result == null || !result.isArray() || result.isEmpty()) {
                return null;
            }
            String spending = textField(result.get(0), "spendingtxid");
            return spending != null && !spending.isBlank() ? spending.trim() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Merge listunspent + scantxoutset for an address (dedupe by txid:vout, keep max confs).
     */
    default List<AddressUtxo> getUnspentOutputsMerged(String address) {
        if (address == null || address.isBlank()) {
            return List.of();
        }
        Map<String, AddressUtxo> byOutpoint = new LinkedHashMap<>();
        for (AddressUtxo utxo : getUnspentOutputs(address.trim())) {
            byOutpoint.put(utxo.txid() + ":" + utxo.vout(), utxo);
        }
        for (AddressUtxo utxo : getUnspentOutputsFromScan(address.trim(), 1)) {
            String key = utxo.txid() + ":" + utxo.vout();
            AddressUtxo existing = byOutpoint.get(key);
            if (existing == null || utxo.confirmations() > existing.confirmations()) {
                byOutpoint.put(key, new AddressUtxo(
                        utxo.txid(),
                        utxo.vout(),
                        utxo.valueSats(),
                        utxo.scriptPubKey(),
                        utxo.confirmations(),
                        address.trim()));
            }
        }
        return List.copyOf(byOutpoint.values());
    }

    private static List<AddressUtxo> parseScantxoutsetUnspents(JsonNode result, String fallbackAddress) {
        if (result == null || result.isNull() || result.isMissingNode()) {
            return List.of();
        }
        long tipHeight = result.path("height").isIntegralNumber() ? result.path("height").asLong() : 0L;
        JsonNode unspents = result.path("unspents");
        if (!unspents.isArray()) {
            return List.of();
        }
        List<AddressUtxo> results = new ArrayList<>();
        for (JsonNode utxo : unspents) {
            String txid = textField(utxo, "txid");
            JsonNode vout = utxo.path("vout");
            long valueSats = parseUtxoValueSats(utxo);
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
            results.add(new AddressUtxo(
                    txid,
                    vout.asInt(),
                    valueSats,
                    textField(utxo, "scriptPubKey"),
                    confs,
                    fallbackAddress));
        }
        return results;
    }

    private long estimateSmartFeeForTarget(int confirmationTarget, long fallbackSatPerVByte) {
        if (confirmationTarget <= 0) {
            return fallbackSatPerVByte;
        }

        try {
            JsonNode feeEstimate = unwrapResult(executeRpc("estimatesmartfee", confirmationTarget));
            JsonNode feeRateNode = feeEstimate != null ? feeEstimate.path("feerate") : null;
            if (feeRateNode == null || !feeRateNode.isNumber()) {
                return fallbackSatPerVByte;
            }

            BigDecimal btcPerKvB = feeRateNode.decimalValue();
            if (btcPerKvB.signum() <= 0) {
                return fallbackSatPerVByte;
            }

            return Math.max(1L, btcPerKvB
                    .multiply(new BigDecimal("100000000"))
                    .divide(new BigDecimal("1000"), 0, RoundingMode.CEILING)
                    .longValueExact());
        } catch (RuntimeException e) {
            return fallbackSatPerVByte;
        }
    }

    private long scanDescriptorBalance(String descriptor, int range) {
        Map<String, Object> scanObject = new LinkedHashMap<>();
        scanObject.put("desc", descriptor);
        scanObject.put("range", range);

        JsonNode result = unwrapResult(executeRpc("scantxoutset", "start", List.of(scanObject)));
        if (result == null || result.isNull() || result.isMissingNode()) {
            return 0L;
        }

        JsonNode totalAmount = result.path("total_amount");
        if (!totalAmount.isNumber()) {
            return 0L;
        }

        return btcToSats(totalAmount.decimalValue());
    }

    private static JsonNode unwrapResult(JsonNode node) {
        if (node != null && node.has("result") && !node.get("result").isNull()) {
            return node.get("result");
        }
        return node;
    }

    private static long parseBtcBalanceToSats(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return 0L;
        }

        if (node.isNumber()) {
            return btcToSats(node.decimalValue());
        }

        JsonNode mine = node.path("mine");
        if (!mine.isMissingNode()) {
            BigDecimal total = decimalField(mine, "trusted")
                    .add(decimalField(mine, "untrusted_pending"))
                    .add(decimalField(mine, "immature"));
            return btcToSats(total);
        }

        if (node.path("balance").isNumber()) {
            return btcToSats(node.path("balance").decimalValue());
        }

        return 0L;
    }

    private static BigDecimal decimalField(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isNumber() ? value.decimalValue() : BigDecimal.ZERO;
    }

    private static long parseUtxoValueSats(JsonNode node) {
        JsonNode value = node.path("value");
        if (value.isIntegralNumber()) {
            return value.asLong();
        }

        JsonNode satoshis = node.path("satoshis");
        if (satoshis.isIntegralNumber()) {
            return satoshis.asLong();
        }

        JsonNode amount = node.path("amount");
        if (amount.isNumber()) {
            return btcToSats(amount.decimalValue());
        }

        return 0L;
    }

    private static String textField(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text != null && !text.isBlank() ? text : null;
    }

    private static long btcToSats(BigDecimal btc) {
        if (btc == null || btc.signum() <= 0) {
            return 0L;
        }
        FinancialAmountValidator.requireBtcPrecision(btc, "btc");
        return btc.multiply(new BigDecimal("100000000"))
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }
}
