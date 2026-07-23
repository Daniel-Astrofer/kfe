package com.kerosene.kfe.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import source.common.financial.FinancialNotificationPort;
import com.kerosene.kfe.application.transaction.KfeBalanceMovementRecorder;
import com.kerosene.kfe.application.transaction.KfeLedgerMovementTypes;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfePaymentRequestEntity;
import com.kerosene.kfe.model.KfePaymentRequestStatus;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.rail.BlockchainClient;
import com.kerosene.kfe.repository.KfeBalanceMovementRepository;
import com.kerosene.kfe.repository.KfePaymentRequestRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "kfe.payment-request-monitor.enabled", havingValue = "true", matchIfMissing = true)
public class KfePaymentRequestOnchainMonitor {

    private static final Logger log = LoggerFactory.getLogger(KfePaymentRequestOnchainMonitor.class);
    private static final Pattern TXID = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final BigDecimal SATOSHIS_PER_BTC = new BigDecimal("100000000");
    private static final String ASSET_BTC = "BTC";

    private final KfePaymentRequestRepository paymentRequestRepository;
    private final KfeTransactionRepository transactionRepository;
    private final KfeWalletRepository walletRepository;
    private final KfeBalanceMovementRepository movementRepository;
    private final ObjectProvider<BlockchainClient> blockchainClient;
    private final KfePricingService pricingService;
    private final KfeBalanceService balanceService;
    private final KfeBalanceMovementRecorder movementRecorder;
    private final KfeFeeSettlementService feeSettlementService;
    private final KfeAuditLogService auditLogService;
    private final KfeStatementService statementService;
    private final KfeResponseMapper responseMapper;
    private final KfeDashboardPublisher dashboardPublisher;
    private final FinancialNotificationPort notificationPort;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService;
    private final ObjectProvider<KfeBalanceMetrics> balanceMetrics;
    private final int batchSize;
    private final int minConfirmations;

    public KfePaymentRequestOnchainMonitor(
            KfePaymentRequestRepository paymentRequestRepository,
            KfeTransactionRepository transactionRepository,
            KfeWalletRepository walletRepository,
            KfeBalanceMovementRepository movementRepository,
            ObjectProvider<BlockchainClient> blockchainClient,
            KfePricingService pricingService,
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder,
            KfeFeeSettlementService feeSettlementService,
            KfeAuditLogService auditLogService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            FinancialNotificationPort notificationPort,
            TransactionTemplate transactionTemplate,
            ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics,
            @Value("${kfe.payment-request-monitor.batch-size:50}") int batchSize,
            @Value("${kfe.payment-request-monitor.onchain.min-confirmations:${bitcoin.min-confirmations:3}}")
            int minConfirmations) {
        this.paymentRequestRepository = paymentRequestRepository;
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.movementRepository = movementRepository;
        this.blockchainClient = blockchainClient;
        this.pricingService = pricingService;
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
        this.feeSettlementService = feeSettlementService;
        this.auditLogService = auditLogService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.notificationPort = notificationPort;
        this.transactionTemplate = transactionTemplate;
        this.onchainBalanceSyncService = onchainBalanceSyncService;
        this.balanceMetrics = balanceMetrics;
        this.batchSize = Math.max(1, batchSize);
        // Allow 0 for mempool-settlement (local/dev). Production should keep >= 1.
        this.minConfirmations = Math.max(0, minConfirmations);
    }

    @Scheduled(
            fixedDelayString = "${kfe.payment-request-monitor.fixed-delay-ms:1000}",
            initialDelayString = "${kfe.payment-request-monitor.initial-delay-ms:2000}")
    public void reconcileOpenOnchainPaymentRequests() {
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }

        List<KfePaymentRequestEntity> requests = paymentRequestRepository.findByStatusInAndRailOrderByCreatedAtAsc(
                List.of(KfePaymentRequestStatus.OPEN, KfePaymentRequestStatus.EXPIRED),
                KfeRail.ONCHAIN,
                PageRequest.of(0, batchSize));
        for (KfePaymentRequestEntity request : requests) {
            try {
                findObservedPayment(client, request)
                        .ifPresent(payment -> transactionTemplate.executeWithoutResult(
                                status -> reconcileObservedPayment(request.getId(), payment)));
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE PaymentRequest Monitor] reconciliation failed paymentRequestId={}: {}",
                        request.getId(),
                        exception.getMessage());
            }
        }
    }

    private void reconcileObservedPayment(UUID paymentRequestId, ObservedPayment payment) {
        if (payment.confirmations() >= minConfirmations) {
            settlePaymentRequest(paymentRequestId, payment);
        } else {
            observePaymentRequest(paymentRequestId, payment);
        }
    }

    private Optional<ObservedPayment> findObservedPayment(BlockchainClient client, KfePaymentRequestEntity request) {
        JsonNode received = client.getAddressTransactions(request.getAddress());
        if (received == null || !received.isArray()) {
            return Optional.empty();
        }

        for (JsonNode entry : received) {
            String txid = txidFromReceivedEntry(entry);
            int confirmations = confirmations(entry);
            long observedSats = amountSats(entry);
            if (txid != null
                    && observedSats > 0L
                    && satisfiesRequestedAmount(request, observedSats)) {
                return Optional.of(new ObservedPayment(txid, observedSats, confirmations, entry.toString()));
            }
        }
        return Optional.empty();
    }

    @Transactional
    public void observePaymentRequest(java.util.UUID paymentRequestId, ObservedPayment payment) {
        KfePaymentRequestEntity request = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        if (!canObserve(request)) {
            return;
        }
        if (!satisfiesRequestedAmount(request, payment.observedSats())) {
            return;
        }

        KfeTransactionEntity existing = findObservedOrSettledTransaction(payment.txid()).orElse(null);
        if (existing != null) {
            boolean patched = false;
            if (existing.getConfirmations() < payment.confirmations()) {
                existing.setConfirmations(payment.confirmations());
                patched = true;
                notifyDepositConfirmationProgress(request, existing, payment);
            }
            if (patched) {
                transactionRepository.save(existing);
                statementService.recordUserStatement(
                        request.getUserId(),
                        request.getWalletId(),
                        existing,
                        new LinkedHashMap<>(
                                responseMapper.buildDisplayPayload(existing, request.getUserId())));
                dashboardPublisher.publishAfterCommit(request.getUserId());
            } else {
                recordObservedStatementIfAbsent(
                        request, existing, payment, existing.getReceiverAmountSats());
            }
            return;
        }

        KfePricingService.Quote quote = pricingService.quote(
                KfeRail.ONCHAIN,
                KfeDirection.INBOUND,
                payment.observedSats(),
                0L);

        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(request.getUserId());
        tx.setIdempotencyKey("payment-request:" + request.getId() + ":" + payment.txid());
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.INBOUND);
        tx.setDestinationWalletId(request.getWalletId());
        tx.setGrossAmountSats(quote.grossAmountSats());
        tx.setReceiverAmountSats(quote.receiverAmountSats());
        tx.setNetworkFeeSats(quote.networkFeeSats());
        tx.setKeroseneFeeSats(quote.keroseneFeeSats());
        tx.setTotalDebitSats(quote.totalDebitSats());
        tx.setProvider("BITCOIN_CORE_PAYMENT_REQUEST_MONITOR");
        tx.setProviderReference(payment.txid());
        tx.setBlockchainTxid(payment.txid());
        tx.setConfirmations(payment.confirmations());
        tx.setStatus(KfeTransactionStatus.VALIDATING);
        transactionRepository.save(tx);
        notifyDepositDetected(request, tx, payment);
        recordObservedStatementIfAbsent(request, tx, payment, quote.receiverAmountSats());

        auditLogService.record(
                "KFE_PAYMENT_REQUEST_OBSERVED",
                tx.getId(),
                request.getWalletId(),
                null,
                KfeTransactionStatus.VALIDATING,
                Map.of(
                        "paymentRequestId", request.getId().toString(),
                        "publicId", request.getPublicId(),
                        "txid", payment.txid(),
                        "observedSats", payment.observedSats(),
                        "confirmations", payment.confirmations()));
        dashboardPublisher.publishAfterCommit(request.getUserId());
    }

    @Transactional
    public void settlePaymentRequest(java.util.UUID paymentRequestId, ObservedPayment payment) {
        KfePaymentRequestEntity request = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        if (!canObserve(request)) {
            return;
        }
        if (!satisfiesRequestedAmount(request, payment.observedSats())) {
            return;
        }
        KfeTransactionEntity existing = findObservedOrSettledTransaction(payment.txid()).orElse(null);
        if (existing != null && existing.getStatus() == KfeTransactionStatus.SETTLED) {
            // Deposit may already be credited by custodial observer — still close the PR.
            if (request.getStatus() != KfePaymentRequestStatus.PAID) {
                request.markPaid(existing.getId());
                paymentRequestRepository.save(request);
                recordPaymentRequestStatement(
                        request,
                        existing,
                        payment,
                        Math.max(0L, existing.getReceiverAmountSats()));
                notifyPaymentRequestPaid(
                        request, existing, Math.max(0L, existing.getReceiverAmountSats()));
                dashboardPublisher.publishAfterCommit(request.getUserId());
                log.info(
                        "[KFE PR Monitor] markPaid on already-SETTLED inbound walletId={} publicId={} txid={}",
                        request.getWalletId(),
                        request.getPublicId(),
                        payment.txid());
            }
            return;
        }

        KfePricingService.Quote quote = pricingService.quote(
                KfeRail.ONCHAIN,
                KfeDirection.INBOUND,
                payment.observedSats(),
                0L);

        KfeTransactionEntity tx = existing != null ? existing : new KfeTransactionEntity();
        if (existing == null) {
            tx.setUserId(request.getUserId());
            tx.setIdempotencyKey("payment-request:" + request.getId() + ":" + payment.txid());
            tx.setRail(KfeRail.ONCHAIN);
            tx.setDirection(KfeDirection.INBOUND);
            tx.setDestinationWalletId(request.getWalletId());
            tx.setProvider("BITCOIN_CORE_PAYMENT_REQUEST_MONITOR");
            tx.setProviderReference(payment.txid());
            tx.setBlockchainTxid(payment.txid());
        }
        tx.setGrossAmountSats(quote.grossAmountSats());
        tx.setReceiverAmountSats(quote.receiverAmountSats());
        tx.setNetworkFeeSats(quote.networkFeeSats());
        tx.setKeroseneFeeSats(quote.keroseneFeeSats());
        tx.setTotalDebitSats(quote.totalDebitSats());
        tx.setConfirmations(payment.confirmations());
        tx.setStatus(KfeTransactionStatus.SETTLED);
        tx = transactionRepository.save(tx);

        creditInboundToWallet(request.getWalletId(), tx.getId(), quote.receiverAmountSats());
        feeSettlementService.creditKeroseneFee(tx);
        request.markPaid(tx.getId());
        paymentRequestRepository.save(request);

        auditLogService.record(
                "KFE_PAYMENT_REQUEST_PAID",
                tx.getId(),
                request.getWalletId(),
                null,
                KfeTransactionStatus.SETTLED,
                Map.of(
                        "paymentRequestId", request.getId().toString(),
                        "publicId", request.getPublicId(),
                        "txid", payment.txid(),
                        "observedSats", payment.observedSats(),
                        "creditedSats", quote.receiverAmountSats(),
                        "confirmations", payment.confirmations()));
        recordPaymentRequestStatement(request, tx, payment, quote.receiverAmountSats());
        notifyPaymentRequestPaid(request, tx, quote.receiverAmountSats());
        dashboardPublisher.publishAfterCommit(request.getUserId());
    }

    private Optional<KfeTransactionEntity> findObservedOrSettledTransaction(String txid) {
        return transactionRepository.findByProviderReferenceForUpdate(txid).stream()
                .filter(tx -> tx.getStatus() == KfeTransactionStatus.VALIDATING
                        || tx.getStatus() == KfeTransactionStatus.SETTLED)
                .findFirst();
    }

    private boolean canObserve(KfePaymentRequestEntity request) {
        return request.getStatus() == KfePaymentRequestStatus.OPEN
                || request.getStatus() == KfePaymentRequestStatus.EXPIRED;
    }

    private void recordObservedStatementIfAbsent(
            KfePaymentRequestEntity request,
            KfeTransactionEntity tx,
            ObservedPayment payment,
            long creditedSats) {
        // Upsert canonical payload so conf/status advance in place (same transactionId).
        recordPaymentRequestStatement(request, tx, payment, creditedSats);
    }

    private void recordPaymentRequestStatement(
            KfePaymentRequestEntity request,
            KfeTransactionEntity tx,
            ObservedPayment payment,
            long creditedSats) {
        java.util.Map<String, Object> payload =
                new java.util.LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, request.getUserId()));
        payload.put("paymentRequestId", request.getId().toString());
        payload.put("publicId", request.getPublicId());
        payload.put("txid", payment.txid());
        payload.put("observedSats", payment.observedSats());
        payload.put("creditedSats", creditedSats);
        payload.put(
                "rawPaymentHash",
                Integer.toHexString(payment.rawPayload() != null ? payment.rawPayload().hashCode() : 0));
        statementService.recordUserStatement(request.getUserId(), request.getWalletId(), tx, payload);
    }

    private void notifyPaymentRequestPaid(KfePaymentRequestEntity request, KfeTransactionEntity tx, long creditedSats) {
        try {
            notificationPort.notifyPaymentRequestDepositConfirmed(
                    request.getUserId(),
                    tx.getId(),
                    request.getId(),
                    request.getPublicId(),
                    request.getWalletId(),
                    request.getRail().name(),
                    creditedSats);
        } catch (RuntimeException exception) {
            log.warn(
                    "KFE payment request was credited but notification failed. paymentRequestId={} error={}",
                    request.getId(),
                    exception.getMessage());
        }
    }

    /**
     * Balance model by custody:
     * <ul>
     *   <li>WATCH_ONLY — only blockchain observed balance (absolute resync; never internal available).</li>
     *   <li>CUSTODIAL_ONCHAIN — internal available (authorization) + chain observed resync.</li>
     *   <li>INTERNAL — internal available only.</li>
     * </ul>
     */
    private void creditInboundToWallet(UUID walletId, UUID transactionId, long amountSats) {
        KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
        boolean watchOnly = wallet != null
                && (wallet.getKind() == KfeWalletKind.WATCH_ONLY || !wallet.isSpendable());
        if (watchOnly) {
            long chainSats = resyncChainObserved(walletId);
            long recorded = chainSats >= 0L ? chainSats : amountSats;
            movementRecorder.record(
                    transactionId, walletId, "CHAIN_OBSERVED_SYNC", recorded, null, "OBSERVED");
            return;
        }
        // Dual path: custodial observer / inbound settlement may have credited the same tx.
        if (movementRepository.existsByTransactionIdAndMovementTypeIn(
                transactionId, KfeLedgerMovementTypes.USER_AVAILABLE_CREDIT_TYPES)) {
            log.info(
                    "[KFE PR Monitor] skip dual credit transactionId={} walletId={} amount={}",
                    transactionId,
                    walletId,
                    amountSats);
            recordDualSkip("payment-request");
            if (wallet != null && wallet.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN) {
                resyncChainObserved(walletId);
            }
            return;
        }
        boolean wrote = movementRecorder.record(
                transactionId,
                walletId,
                KfeLedgerMovementTypes.CREDIT_PAYMENT_REQUEST,
                amountSats,
                null,
                "AVAILABLE");
        if (!wrote) {
            log.info(
                    "[KFE PR Monitor] credit race lost transactionId={} walletId={}",
                    transactionId,
                    walletId);
            recordDualSkip("payment-request-race");
            if (wallet != null && wallet.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN) {
                resyncChainObserved(walletId);
            }
            return;
        }
        balanceService.creditAvailable(walletId, ASSET_BTC, amountSats);
        if (wallet != null && wallet.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN) {
            resyncChainObserved(walletId);
        }
    }

    private long resyncChainObserved(UUID walletId) {
        KfeOnchainBalanceSyncService sync = onchainBalanceSyncService.getIfAvailable();
        if (sync == null) {
            return -1L;
        }
        try {
            return sync.syncWallet(walletId);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE PaymentRequest Monitor] chain balance sync failed walletId={}: {}",
                    walletId,
                    exception.getMessage());
            return -1L;
        }
    }

    private void recordDualSkip(String path) {
        KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.recordDualCreditSkip(path);
        }
    }

    private boolean satisfiesRequestedAmount(KfePaymentRequestEntity request, long observedSats) {
        return request.getAmountSats() == null || observedSats >= request.getAmountSats();
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

    private int confirmations(JsonNode node) {
        JsonNode confirmations = node.path("confirmations");
        return confirmations.isIntegralNumber() ? Math.max(0, confirmations.asInt()) : 0;
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

    private boolean looksLikeTxid(String value) {
        return value != null && TXID.matcher(value.trim()).matches();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private void notifyDepositDetected(KfePaymentRequestEntity request, KfeTransactionEntity tx, ObservedPayment payment) {
        try {
            notificationPort.notifyDepositDetected(
                    request.getUserId(),
                    tx.getId(),
                    request.getWalletId(),
                    request.getRail().name(),
                    tx.getReceiverAmountSats(),
                    payment.confirmations());
        } catch (RuntimeException exception) {
            log.warn(
                    "KFE deposit detected notification failed. paymentRequestId={} error={}",
                    request.getId(),
                    exception.getMessage());
        }
    }

    private void notifyDepositConfirmationProgress(KfePaymentRequestEntity request, KfeTransactionEntity tx, ObservedPayment payment) {
        try {
            notificationPort.notifyDepositConfirmationProgress(
                    request.getUserId(),
                    tx.getId(),
                    request.getWalletId(),
                    request.getRail().name(),
                    tx.getReceiverAmountSats(),
                    payment.confirmations());
        } catch (RuntimeException exception) {
            log.warn(
                    "KFE deposit confirmation progress notification failed. paymentRequestId={} error={}",
                    request.getId(),
                    exception.getMessage());
        }
    }

    public record ObservedPayment(String txid, long observedSats, int confirmations, String rawPayload) {
    }
}
