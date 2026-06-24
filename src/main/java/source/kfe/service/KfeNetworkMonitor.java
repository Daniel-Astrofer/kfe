package source.kfe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import source.kfe.model.KfeExecutionOutboxEntity;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.rail.BlockchainClient;
import source.kfe.rail.CustodyGateway;
import source.kfe.rail.LightningInvoiceGateway;
import source.kfe.repository.KfeExecutionOutboxRepository;
import source.kfe.repository.KfeTransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "kfe.network-monitor.enabled", havingValue = "true")
public class KfeNetworkMonitor {

    private static final Logger log = LoggerFactory.getLogger(KfeNetworkMonitor.class);
    private static final List<String> INBOUND_OPERATIONS = List.of("ONCHAIN_INBOUND", "LIGHTNING_INBOUND");
    private static final Pattern TXID = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final BigDecimal SATOSHIS_PER_BTC = new BigDecimal("100000000");

    private final KfeExecutionOutboxRepository outboxRepository;
    private final KfeTransactionRepository transactionRepository;
    private final KfeInboundSettlementService settlementService;
    private final ObjectProvider<BlockchainClient> blockchainClient;
    private final ObjectProvider<LightningInvoiceGateway> lightningInvoiceGateway;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int minOnchainConfirmations;

    public KfeNetworkMonitor(
            KfeExecutionOutboxRepository outboxRepository,
            KfeTransactionRepository transactionRepository,
            KfeInboundSettlementService settlementService,
            ObjectProvider<BlockchainClient> blockchainClient,
            @Qualifier("kfeExternalLightningInvoiceGateway")
            ObjectProvider<LightningInvoiceGateway> lightningInvoiceGateway,
            ObjectMapper objectMapper,
            @Value("${kfe.network-monitor.batch-size:50}") int batchSize,
            @Value("${kfe.network-monitor.onchain.min-confirmations:${bitcoin.min-confirmations:3}}")
            int minOnchainConfirmations) {
        this.outboxRepository = outboxRepository;
        this.transactionRepository = transactionRepository;
        this.settlementService = settlementService;
        this.blockchainClient = blockchainClient;
        this.lightningInvoiceGateway = lightningInvoiceGateway;
        this.objectMapper = objectMapper;
        this.batchSize = Math.max(1, batchSize);
        this.minOnchainConfirmations = Math.max(1, minOnchainConfirmations);
    }

    @Scheduled(
            fixedDelayString = "${kfe.network-monitor.fixed-delay-ms:30000}",
            initialDelayString = "${kfe.network-monitor.initial-delay-ms:20000}")
    public void reconcileInbound() {
        List<KfeExecutionOutboxEntity> candidates = outboxRepository.findInboundReconciliationCandidates(
                INBOUND_OPERATIONS,
                PageRequest.of(0, batchSize));
        for (KfeExecutionOutboxEntity outbox : candidates) {
            try {
                inspect(outbox);
            } catch (RuntimeException exception) {
                log.warn("[KFE Monitor] Inbound reconciliation failed outboxId={}: {}",
                        outbox.getId(), exception.getMessage());
            }
        }
    }

    private void inspect(KfeExecutionOutboxEntity outbox) {
        Optional<KfeTransactionEntity> optionalTx = transactionRepository.findById(outbox.getTransactionId());
        if (optionalTx.isEmpty()) {
            return;
        }
        KfeTransactionEntity tx = optionalTx.get();
        if (tx.getStatus() != KfeTransactionStatus.REQUIRES_RECONCILIATION
                && tx.getStatus() != KfeTransactionStatus.EXECUTING) {
            return;
        }

        JsonNode payload = payload(outbox);
        if (tx.getRail() == KfeRail.ONCHAIN) {
            inspectOnchain(outbox, tx, payload);
        } else if (tx.getRail() == KfeRail.LIGHTNING) {
            inspectLightning(outbox, tx, payload);
        }
    }

    private void inspectOnchain(KfeExecutionOutboxEntity outbox, KfeTransactionEntity tx, JsonNode payload) {
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }

        Optional<OnchainProof> proof = findOnchainProof(client, outbox, tx, payload);
        if (proof.isEmpty() || proof.get().confirmations() < minOnchainConfirmations) {
            return;
        }

        OnchainProof value = proof.get();
        settlementService.settle(new KfeInboundSettlementService.InboundSettlementProof(
                tx.getId(),
                outbox.getId(),
                "BITCOIN_CORE_MONITOR",
                value.txid(),
                value.txid(),
                value.observedAmountSats(),
                value.confirmations(),
                value.rawPayload()));
    }

    private Optional<OnchainProof> findOnchainProof(
            BlockchainClient client,
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            JsonNode payload) {
        String targetAddress = targetAddress(payload);
        String txid = txid(outbox, tx, payload);
        if (txid != null) {
            return loadOnchainTx(client, txid, targetAddress);
        }
        if (targetAddress == null) {
            return Optional.empty();
        }

        JsonNode received = client.getAddressTransactions(targetAddress);
        if (received == null || !received.isArray()) {
            return Optional.empty();
        }
        for (JsonNode entry : received) {
            long observed = amountSats(entry);
            int confirmations = confirmations(entry);
            String observedTxid = txidFromReceivedEntry(entry);
            if (observedTxid != null
                    && confirmations >= minOnchainConfirmations
                    && observed >= tx.getGrossAmountSats()) {
                return loadOnchainTx(client, observedTxid, targetAddress)
                        .or(() -> Optional.of(new OnchainProof(
                                observedTxid,
                                observed,
                                confirmations,
                                entry.toString())));
            }
        }
        return Optional.empty();
    }

    private Optional<OnchainProof> loadOnchainTx(
            BlockchainClient client,
            String txid,
            String targetAddress) {
        try {
            JsonNode raw = client.getRawTransaction(txid, true);
            int confirmations = confirmations(raw);
            long observed = amountSats(raw, targetAddress);
            if (observed <= 0L) {
                observed = amountSats(raw);
            }
            return Optional.of(new OnchainProof(txid, observed, confirmations, raw.toString()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void inspectLightning(KfeExecutionOutboxEntity outbox, KfeTransactionEntity tx, JsonNode payload) {
        LightningInvoiceGateway gateway = lightningInvoiceGateway.getIfAvailable();
        if (gateway == null || !gateway.isLive()) {
            return;
        }

        String paymentRequest = paymentRequest(payload);
        String paymentHash = firstNonBlank(
                tx.getPaymentHash(),
                text(payload, "paymentHash", "payment_hash"),
                looksLikeLightningInvoice(text(payload, "externalReference")) ? null : text(payload, "externalReference"));
        String providerReference = firstNonBlank(
                tx.getProviderReference(),
                outbox.getProviderReference(),
                text(payload, "providerReference", "provider_reference", "invoiceId", "invoice_id"));

        CustodyGateway.IncomingLightningInvoiceStatus status = gateway.getLightningInvoiceStatus(
                new CustodyGateway.LightningInvoiceStatusCommand(
                        tx.getUserId(),
                        null,
                        null,
                        paymentHash,
                        providerReference,
                        paymentRequest));
        if (!isSettled(status.status()) || status.receivedSats() == null) {
            return;
        }
        settlementService.settle(new KfeInboundSettlementService.InboundSettlementProof(
                tx.getId(),
                outbox.getId(),
                gateway.providerName(),
                firstNonBlank(providerReference, paymentHash),
                firstNonBlank(paymentHash, providerReference),
                status.receivedSats(),
                1,
                status.rawPayload()));
    }

    private JsonNode payload(KfeExecutionOutboxEntity outbox) {
        if (outbox.getPayloadJson() == null || outbox.getPayloadJson().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(outbox.getPayloadJson());
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private String txid(KfeExecutionOutboxEntity outbox, KfeTransactionEntity tx, JsonNode payload) {
        String externalReference = text(payload, "externalReference");
        return firstNonBlank(
                tx.getBlockchainTxid(),
                tx.getProviderReference(),
                outbox.getProviderReference(),
                text(payload, "txid", "blockchainTxid", "blockchain_txid", "providerReference", "provider_reference"),
                looksLikeTxid(externalReference) ? externalReference : null);
    }

    private String targetAddress(JsonNode payload) {
        String externalReference = text(payload, "externalReference");
        return firstNonBlank(
                text(payload, "address", "receiveAddress", "receive_address", "destinationAddress", "destination_address"),
                !looksLikeTxid(externalReference) && !looksLikeLightningInvoice(externalReference) ? externalReference : null);
    }

    private String paymentRequest(JsonNode payload) {
        String externalReference = text(payload, "externalReference");
        return firstNonBlank(
                text(payload, "paymentRequest", "payment_request", "bolt11", "invoice"),
                looksLikeLightningInvoice(externalReference) ? externalReference : null);
    }

    private String txidFromReceivedEntry(JsonNode entry) {
        String direct = text(entry, "txid");
        if (looksLikeTxid(direct)) {
            return direct;
        }
        JsonNode txids = entry.path("txids");
        if (txids.isArray() && txids.size() > 0) {
            String txid = txids.get(0).asText();
            return looksLikeTxid(txid) ? txid : null;
        }
        return null;
    }

    private long amountSats(JsonNode node) {
        long sats = satsField(node, "sats", "satoshis", "amountSats", "amount_sats", "valueSats", "value_sats");
        if (sats > 0L) {
            return sats;
        }
        long amount = amountFromBtcField(node, "amount");
        if (amount > 0L) {
            return amount;
        }
        return amountFromBtcField(node, "value");
    }

    private long amountSats(JsonNode node, String targetAddress) {
        if (targetAddress == null || targetAddress.isBlank()) {
            return amountSats(node);
        }

        long total = 0L;
        JsonNode details = node.path("details");
        if (details.isArray()) {
            for (JsonNode item : details) {
                if ("receive".equalsIgnoreCase(text(item, "category"))
                        && targetAddress.equals(text(item, "address"))) {
                    total += amountSats(item);
                }
            }
        }

        JsonNode vout = node.path("vout");
        if (vout.isArray()) {
            for (JsonNode output : vout) {
                if (scriptPaysAddress(output.path("scriptPubKey"), targetAddress)) {
                    total += amountSats(output);
                }
            }
        }
        return total > 0L ? total : amountSats(node);
    }

    private boolean scriptPaysAddress(JsonNode scriptPubKey, String targetAddress) {
        if (targetAddress.equals(text(scriptPubKey, "address"))) {
            return true;
        }
        JsonNode addresses = scriptPubKey.path("addresses");
        if (addresses.isArray()) {
            for (JsonNode address : addresses) {
                if (targetAddress.equals(address.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private int confirmations(JsonNode node) {
        JsonNode confirmations = node.path("confirmations");
        return confirmations.isIntegralNumber() ? Math.max(0, confirmations.asInt()) : 0;
    }

    private long satsField(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isIntegralNumber()) {
                return Math.max(0L, value.asLong());
            }
            if (value.isTextual()) {
                try {
                    return Math.max(0L, Long.parseLong(value.asText()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0L;
    }

    private long amountFromBtcField(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return 0L;
        }
        BigDecimal btc = value.decimalValue();
        if (btc.signum() <= 0) {
            return 0L;
        }
        return btc.multiply(SATOSHIS_PER_BTC)
                .setScale(0, RoundingMode.DOWN)
                .longValue();
    }

    private boolean isSettled(String status) {
        if (status == null) {
            return false;
        }
        return switch (status.trim().toUpperCase()) {
            case "SETTLED", "CONFIRMED", "PAID", "SUCCEEDED", "COMPLETE", "COMPLETED" -> true;
            default -> false;
        };
    }

    private boolean looksLikeTxid(String value) {
        return value != null && TXID.matcher(value.trim()).matches();
    }

    private boolean looksLikeLightningInvoice(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return lower.startsWith("lnbc") || lower.startsWith("lntb") || lower.startsWith("lnbcrt");
    }

    private String text(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record OnchainProof(
            String txid,
            long observedAmountSats,
            int confirmations,
            String rawPayload) {
    }
}
