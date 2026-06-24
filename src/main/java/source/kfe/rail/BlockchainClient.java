package source.kfe.rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import source.common.validation.FinancialAmountValidator;

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

    record AddressUtxo(String txid, int vout, long valueSats, String scriptPubKey) {
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
                    results.add(new AddressUtxo(
                            txid,
                            vout.asInt(),
                            valueSats,
                            textField(utxo, "scriptPubKey")));
                }
            }
            return results;
        } catch (RuntimeException e) {
            return List.of();
        }
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
