package source.kfe.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import source.common.financial.FinancialOperationsAdminPort;
import source.common.infra.logging.LogSanitizer;
import source.kfe.model.KfeAuditLogEntity;
import source.kfe.model.KfeExecutionOutboxEntity;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.rail.BitcoinCoreRpcClient;
import source.kfe.rail.BlockchainClient;
import source.kfe.rail.LightningClient;
import source.kfe.repository.KfeAuditLogRepository;
import source.kfe.repository.KfeExecutionOutboxRepository;
import source.kfe.repository.KfeTransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class KfeFinancialOperationsAdminAdapter implements FinancialOperationsAdminPort {

    private static final BigDecimal SATOSHIS_PER_BITCOIN = new BigDecimal("100000000");

    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    private final ObjectProvider<LightningClient> lightningClient;
    private final KfeAuditLogRepository auditLogRepository;
    private final KfeTransactionRepository transactionRepository;
    private final KfeExecutionOutboxRepository outboxRepository;

    public KfeFinancialOperationsAdminAdapter(
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            ObjectProvider<LightningClient> lightningClient,
            KfeAuditLogRepository auditLogRepository,
            KfeTransactionRepository transactionRepository,
            KfeExecutionOutboxRepository outboxRepository) {
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient;
        this.lightningClient = lightningClient;
        this.auditLogRepository = auditLogRepository;
        this.transactionRepository = transactionRepository;
        this.outboxRepository = outboxRepository;
    }

    @Override
    public Map<String, Object> blockchain() {
        BitcoinCoreRpcClient client = bitcoinCoreRpcClient.getIfAvailable();
        if (client == null) {
            return Map.of(
                    "status", "DOWN",
                    "primarySource", "BITCOIN_CORE_RPC",
                    "checkedAt", Instant.now(),
                    "message", "Bitcoin Core RPC is not configured");
        }

        try {
            JsonNode chain = unwrap(client.executeRpc("getblockchaininfo"));
            JsonNode mempool = unwrap(client.executeRpc("getmempoolinfo"));
            BlockchainClient.FeeRates feeRates = client.estimateSmartFee(2, 3, 6);
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("height", chain.path("blocks").asLong(0));
            state.put("headers", chain.path("headers").asLong(0));
            state.put("bestBlockHash", chain.path("bestblockhash").asText(""));
            state.put("chain", chain.path("chain").asText(""));
            state.put("initialBlockDownload", chain.path("initialblockdownload").asBoolean(false));
            state.put("pruned", chain.path("pruned").asBoolean(false));

            Map<String, Object> mempoolState = new LinkedHashMap<>();
            mempoolState.put("transactions", mempool.path("size").asLong(0));
            mempoolState.put("bytes", mempool.path("bytes").asLong(0));
            mempoolState.put("feesSatPerVByte", Map.of(
                    "fast", feeRates.fastSatPerVByte(),
                    "halfHour", feeRates.halfHourSatPerVByte(),
                    "hour", feeRates.hourSatPerVByte()));

            return Map.of(
                    "status", chain.path("blocks").asLong(0) > 0 ? "UP" : "DEGRADED",
                    "primarySource", "BITCOIN_CORE_RPC",
                    "checkedAt", Instant.now(),
                    "chain", state,
                    "mempool", mempoolState,
                    "message", "KFE Bitcoin provider probe completed");
        } catch (RuntimeException exception) {
            return Map.of(
                    "status", "DOWN",
                    "primarySource", "BITCOIN_CORE_RPC",
                    "checkedAt", Instant.now(),
                    "message", "Bitcoin Core RPC probe failed",
                    "exception", exception.getClass().getSimpleName());
        }
    }

    @Override
    public Map<String, Object> lightning() {
        LightningClient client = lightningClient.getIfAvailable();
        if (client == null) {
            return Map.of(
                    "status", "DOWN",
                    "primarySource", "LIGHTNING_PROVIDER",
                    "checkedAt", Instant.now(),
                    "message", "Lightning provider is not configured");
        }

        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("localBalanceSats", client.getLocalBalance());
            state.put("remoteBalanceSats", client.getRemoteBalance());
            state.put("nodeBalanceSats", client.getLightningNodeBalance());
            state.put("uptime", client.getNodeUptime());
            state.put("lspLatencyMs", client.getLspLatency());
            return Map.of(
                    "status", client.getNodeUptime() > 0 ? "UP" : "DEGRADED",
                    "primarySource", "LIGHTNING_PROVIDER",
                    "checkedAt", Instant.now(),
                    "node", state,
                    "message", "KFE Lightning provider probe completed");
        } catch (RuntimeException exception) {
            return Map.of(
                    "status", "DOWN",
                    "primarySource", "LIGHTNING_PROVIDER",
                    "checkedAt", Instant.now(),
                    "message", "Lightning provider probe failed",
                    "exception", exception.getClass().getSimpleName());
        }
    }

    @Override
    public List<Map<String, Object>> logs(int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        return auditLogRepository.findAllByOrderBySequenceNumberDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toSafeLog)
                .toList();
    }

    @Override
    public Map<String, Object> metrics() {
        List<KfeTransactionEntity> transactions = transactionRepository.findAll();
        List<KfeExecutionOutboxEntity> outboxItems = outboxRepository.findAll();

        Map<KfeTransactionStatus, Long> byStatus = transactions.stream()
                .collect(Collectors.groupingBy(KfeTransactionEntity::getStatus, () -> new EnumMap<>(KfeTransactionStatus.class), Collectors.counting()));
        Map<KfeRail, Long> byRail = transactions.stream()
                .collect(Collectors.groupingBy(KfeTransactionEntity::getRail, () -> new EnumMap<>(KfeRail.class), Collectors.counting()));
        BigDecimal totalVolume = transactions.stream()
                .map(transaction -> satsToBtc(transaction.getGrossAmountSats()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFees = transactions.stream()
                .map(transaction -> satsToBtc(transaction.getKeroseneFeeSats() + transaction.getNetworkFeeSats()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("checkedAt", Instant.now());
        payload.put("totalVolumeBtc", totalVolume);
        payload.put("totalFeesBtc", totalFees);
        payload.put("totalTransactions", transactions.size());
        payload.put("avgTicketBtc", transactions.isEmpty()
                ? BigDecimal.ZERO
                : totalVolume.divide(BigDecimal.valueOf(transactions.size()), 8, RoundingMode.HALF_UP));
        payload.put("confirmedTransactions", byStatus.getOrDefault(KfeTransactionStatus.SETTLED, 0L));
        payload.put("pendingTransactions", pendingCount(byStatus));
        payload.put("failedTransactions", byStatus.getOrDefault(KfeTransactionStatus.FAILED, 0L));
        payload.put("transactionsByStatus", stringifyKeys(byStatus));
        payload.put("transactionsByRail", stringifyKeys(byRail));
        payload.put("executionOutboxByStatus", outboxItems.stream()
                .collect(Collectors.groupingBy(KfeExecutionOutboxEntity::getStatus, Collectors.counting())));
        payload.put("privacyBoundary",
                "Aggregate KFE metrics only; no user timeline, destination, txid, invoice payload, or wallet name.");
        return payload;
    }

    private Map<String, Object> toSafeLog(KfeAuditLogEntity event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sequenceNumber", event.getSequenceNumber());
        row.put("id", event.getId());
        row.put("createdAt", event.getCreatedAt());
        row.put("eventType", event.getEventType());
        row.put("transactionRef", LogSanitizer.fingerprint(event.getTransactionId() != null ? event.getTransactionId().toString() : null));
        row.put("walletRef", LogSanitizer.fingerprint(event.getWalletId() != null ? event.getWalletId().toString() : null));
        row.put("payloadHash", event.getPayloadHash());
        row.put("eventHash", event.getEventHash());
        return row;
    }

    private long pendingCount(Map<KfeTransactionStatus, Long> byStatus) {
        return byStatus.getOrDefault(KfeTransactionStatus.INTENT, 0L)
                + byStatus.getOrDefault(KfeTransactionStatus.VALIDATING, 0L)
                + byStatus.getOrDefault(KfeTransactionStatus.QUORUM_SYNC, 0L)
                + byStatus.getOrDefault(KfeTransactionStatus.LOCKED, 0L)
                + byStatus.getOrDefault(KfeTransactionStatus.EXECUTING, 0L)
                + byStatus.getOrDefault(KfeTransactionStatus.REQUIRES_RECONCILIATION, 0L);
    }

    private BigDecimal satsToBtc(long sats) {
        return BigDecimal.valueOf(sats).divide(SATOSHIS_PER_BITCOIN, 8, RoundingMode.HALF_UP);
    }

    private JsonNode unwrap(JsonNode response) {
        if (response != null && response.has("result")) {
            return response.get("result");
        }
        return response;
    }

    private Map<String, Long> stringifyKeys(Map<?, Long> source) {
        Map<String, Long> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
