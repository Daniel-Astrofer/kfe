package com.kerosene.kfe.service;

import java.time.ZoneOffset;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.application.transaction.KfeBalanceMovementRecorder;
import com.kerosene.kfe.application.transaction.KfeLedgerMovementTypes;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfePaymentRequestEntity;
import com.kerosene.kfe.model.KfePaymentRequestStatus;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.rail.CustodyGateway;
import com.kerosene.kfe.rail.LightningInvoiceGateway;
import com.kerosene.kfe.repository.KfePaymentRequestRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.webhook.KfeWebhookDeliveryService;
import com.kerosene.kfe.webhook.KfeWebhookEvent;
import com.kerosene.common.financial.FinancialNotificationPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polls OPEN Lightning payment requests and credits destination wallets when invoices settle.
 *
 * <p>Stream subscription (ITEM 21): subscribes to real-time LND invoice updates via
 * {@link LightningInvoiceGateway#subscribeInvoices}. Events are processed immediately;
 * the polling loop acts as a reconciliation safety net for stream-missed events.
 *
 * <p>Payment state tracking (ITEM 22): differentiates IN_FLIGHT (HTLC routed, not settled),
 * SUCCEEDED (confirmed by LND), FAILED (payment failed), and UNKNOWN (ambiguous/timeout).
 * Only SUCCEEDED credits the ledger.
 */
@Service
public class KfePaymentRequestLightningMonitor {

    private static final Logger log = LoggerFactory.getLogger(KfePaymentRequestLightningMonitor.class);
    private static final String ASSET_BTC = "BTC";

    /**
     * Lightning payment result state (ITEM 22).
     * <ul>
     *   <li>{@code IN_FLIGHT}: HTLC routed, awaiting settlement — DO NOT credit ledger</li>
     *   <li>{@code SUCCEEDED}: payment confirmed by LND — credit normally</li>
     *   <li>{@code FAILED}: payment failed — release reserve if applicable</li>
     *   <li>{@code UNKNOWN}: timeout or ambiguous result — block until reconciled, query by payment_hash</li>
     * </ul>
     */
    public enum LightningPaymentState {
        IN_FLIGHT,
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }

    /** Stream cursor: last-seen LND add_index (persisted on restart via polling reconciliation). */
    private final AtomicLong lastAddIndex = new AtomicLong(0);
    /** Stream cursor: last-seen LND settle_index. */
    private final AtomicLong lastSettleIndex = new AtomicLong(0);

    private final KfePaymentRequestRepository paymentRequestRepository;
    private final KfeTransactionRepository transactionRepository;
    private final LightningInvoiceGateway lightningInvoiceGateway;
    private final KfePricingService pricingService;
    private final KfeBalanceService balanceService;
    private final KfeBalanceMovementRecorder movementRecorder;
    private final KfeFeeSettlementService feeSettlementService;
    private final KfeAuditLogService auditLogService;
    private final KfeStatementService statementService;
    private final KfeResponseMapper responseMapper;
    private final KfeDashboardPublisher dashboardPublisher;
    private final int batchSize;
    private final boolean enabled;
    private final KfePaymentRequestLightningMonitor self;
    private final KfeWebhookDeliveryService webhookDeliveryService;
    private final FinancialNotificationPort notificationPort;
    private volatile LightningInvoiceGateway.InvoiceSubscription streamSubscription;

    public KfePaymentRequestLightningMonitor(
            KfePaymentRequestRepository paymentRequestRepository,
            KfeTransactionRepository transactionRepository,
            @Qualifier("kfeExternalLightningInvoiceGateway")
            LightningInvoiceGateway lightningInvoiceGateway,
            KfePricingService pricingService,
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder,
            KfeFeeSettlementService feeSettlementService,
            KfeAuditLogService auditLogService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            @Value("${kfe.payment-request-lightning-monitor.enabled:true}") boolean enabled,
            @Value("${kfe.payment-request-lightning-monitor.batch-size:25}") int batchSize,
            @Lazy KfePaymentRequestLightningMonitor self,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            KfeWebhookDeliveryService webhookDeliveryService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            FinancialNotificationPort notificationPort) {
        this.paymentRequestRepository = paymentRequestRepository;
        this.transactionRepository = transactionRepository;
        this.lightningInvoiceGateway = lightningInvoiceGateway;
        this.pricingService = pricingService;
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
        this.feeSettlementService = feeSettlementService;
        this.auditLogService = auditLogService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.self = self;
        this.webhookDeliveryService = webhookDeliveryService;
        this.notificationPort = notificationPort;
    }

    // ------------------------------------------------------------------ //
    //  Stream subscription (ITEM 21)                                     //
    // ------------------------------------------------------------------ //

    /**
     * Start the LND invoice stream subscription on bean init.
     * Falls back silently if the gateway does not support streaming.
     */
    @jakarta.annotation.PostConstruct
    void startStreamSubscription() {
        if (!enabled || !lightningInvoiceGateway.isLive()) {
            log.info("[KFE LN Stream] disabled or gateway not live — skipping stream subscription");
            return;
        }
        try {
            streamSubscription = lightningInvoiceGateway.subscribeInvoices(status -> {
                try {
                    handleStreamInvoiceUpdate(status);
                } catch (RuntimeException e) {
                    log.warn("[KFE LN Stream] handler error: {}", e.getMessage());
                }
            });
            if (streamSubscription != null) {
                log.info("[KFE LN Stream] subscribed (addIndex={} settleIndex={})",
                        lastAddIndex.get(), lastSettleIndex.get());
            } else {
                log.info("[KFE LN Stream] gateway returned null subscription — polling only");
            }
        } catch (RuntimeException e) {
            log.warn("[KFE LN Stream] subscription failed: {} — falling back to polling only", e.getMessage());
        }
    }

    /** Clean up stream subscription on bean destroy. */
    @jakarta.annotation.PreDestroy
    void stopStreamSubscription() {
        LightningInvoiceGateway.InvoiceSubscription sub = streamSubscription;
        if (sub != null) {
            try {
                sub.unsubscribe();
                log.info("[KFE LN Stream] unsubscribed (addIndex={} settleIndex={})",
                        lastAddIndex.get(), lastSettleIndex.get());
            } catch (RuntimeException e) {
                log.warn("[KFE LN Stream] unsubscribe error: {}", e.getMessage());
            }
        }
    }

    /**
     * Process a single invoice update from the stream.
     * Only acts on settled invoices; updates cursor indices.
     */
    void handleStreamInvoiceUpdate(CustodyGateway.IncomingLightningInvoiceStatus status) {
        if (status == null) {
            return;
        }
        // Advance cursor indices from stream
        if (status.addIndex() > 0L) {
            lastAddIndex.updateAndGet(current -> Math.max(current, status.addIndex()));
        }
        if (status.settleIndex() > 0L) {
            lastSettleIndex.updateAndGet(current -> Math.max(current, status.settleIndex()));
        }

        LightningPaymentState state = classifyPaymentState(status.status());
        if (state != LightningPaymentState.SUCCEEDED) {
            return;
        }
        if (status.receivedSats() == null || status.receivedSats() <= 0L) {
            return;
        }
        // Match OPEN LIGHTNING payment request by payment_hash from stream
        if (status.paymentHash() != null && !status.paymentHash().isBlank()) {
            paymentRequestRepository.findFirstByPaymentHashIgnoreCase(status.paymentHash())
                    .filter(pr -> pr.getStatus() == KfePaymentRequestStatus.OPEN)
                    .filter(pr -> pr.getRail() == KfeRail.LIGHTNING)
                    .ifPresent(pr -> {
                        try {
                            settleLightningPaymentRequest(pr, status.receivedSats(), status.rawPayload());
                            log.info("[KFE LN Stream] settled paymentRequestId={} paymentHash={}",
                                    pr.getId(), status.paymentHash());
                        } catch (RuntimeException ex) {
                            log.warn("[KFE LN Stream] settle failed paymentRequestId={}: {}",
                                    pr.getId(), ex.getMessage());
                        }
                    });
        }
    }

    // ------------------------------------------------------------------ //
    //  Polling reconciliation (remains as safety net)                    //
    // ------------------------------------------------------------------ //

    @Scheduled(
            fixedDelayString = "${kfe.payment-request-lightning-monitor.fixed-delay-ms:1000}",
            initialDelayString = "${kfe.payment-request-lightning-monitor.initial-delay-ms:2000}")
    public void reconcileOpenLightningPaymentRequests() {
        if (!enabled || !lightningInvoiceGateway.isLive()) {
            return;
        }
        // Iterate through all pages of OPEN LIGHTNING payment requests.
        // Previously only read page 0, starving the tail when backlog > batchSize.
        int page = 0;
        List<KfePaymentRequestEntity> requests;
        do {
            requests = paymentRequestRepository.findByStatusAndRailOrderByCreatedAtAsc(
                    KfePaymentRequestStatus.OPEN,
                    KfeRail.LIGHTNING,
                    PageRequest.of(page, batchSize));
            for (KfePaymentRequestEntity request : requests) {
                try {
                    probeAndMaybeSettle(request);
                } catch (RuntimeException ex) {
                    log.warn(
                            "[KFE LN PR Monitor] failed paymentRequestId={}: {}",
                            request.getId(),
                            ex.getMessage());
                }
            }
            page++;
        } while (requests.size() == batchSize);
    }

    /**
     * Probe LND without holding a DB connection, then settle in a short transactional method
     * via Spring proxy ({@code self}) so {@code @Transactional} actually applies.
     *
     * <p>Before marking a payment request as EXPIRED, a last-chance LND probe is performed.
     * If the invoice settled near the deadline, the payment is processed (reconciled) rather
     * than orphaned.
     *
     * <p><strong>ITEM 22:</strong> Payment state is classified as IN_FLIGHT, SUCCEEDED,
     * FAILED, or UNKNOWN. Only SUCCEEDED triggers ledger credit. IN_FLIGHT leaves the
     * request open for the next cycle. FAILED/UNKNOWN are logged and audited.
     */
    void probeAndMaybeSettle(KfePaymentRequestEntity request) {
        if (request == null || request.getStatus() != KfePaymentRequestStatus.OPEN) {
            return;
        }
        if (request.getPaymentHash() == null || request.getPaymentHash().isBlank()) {
            return;
        }
        if (request.isExpired(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))) {
            // Last-chance LND probe before expiring — prevents race between LND settlement and KFE clock.
            CustodyGateway.IncomingLightningInvoiceStatus lastChance =
                    lightningInvoiceGateway.getLightningInvoiceStatus(
                            new CustodyGateway.LightningInvoiceStatusCommand(
                                    request.getUserId(),
                                    null,
                                    null,
                                    request.getPaymentHash(),
                                    request.getProviderReference(),
                                    request.getPaymentRequest()));
            LightningPaymentState state = classifyPaymentState(lastChance.status());
            if (state == LightningPaymentState.SUCCEEDED
                    && lastChance.receivedSats() != null && lastChance.receivedSats() > 0L) {
                long received = lastChance.receivedSats();
                if (request.getAmountSats() == null || received >= request.getAmountSats()) {
                    log.warn(
                            "[KFE LN PR Monitor] expired-but-settled invoice publicId={} received={}sats — reconciling",
                            request.getPublicId(),
                            received);
                    self.reconcileExpiredButSettled(request.getId(), received, lastChance.rawPayload());
                } else {
                    log.warn(
                            "[KFE LN PR Monitor] expired-but-underpaid invoice publicId={} expected={} received={} — expiring",
                            request.getPublicId(),
                            request.getAmountSats(),
                            received);
                    if (notificationPort != null) {
                        try {
                            notificationPort.notifyDepositDetected(
                                    request.getUserId(),
                                    null,
                                    request.getWalletId(),
                                    "LIGHTNING",
                                    received,
                                    0);
                        } catch (RuntimeException exception) {
                            log.warn("[KFE LN PR Monitor] underpaid notification failed publicId={}: {}",
                                    request.getPublicId(), exception.getMessage());
                        }
                    }
                    self.expireRequest(request.getId());
                }
                return;
            }
            if (state == LightningPaymentState.IN_FLIGHT) {
                log.info(
                        "[KFE LN PR Monitor] expired-but-in-flight invoice publicId={} — keeping open",
                        request.getPublicId());
                return;
            }
            self.expireRequest(request.getId());
            return;
        }

        CustodyGateway.IncomingLightningInvoiceStatus status = lightningInvoiceGateway.getLightningInvoiceStatus(
                new CustodyGateway.LightningInvoiceStatusCommand(
                        request.getUserId(),
                        null,
                        null,
                        request.getPaymentHash(),
                        request.getProviderReference(),
                        request.getPaymentRequest()));
        LightningPaymentState state = classifyPaymentState(status.status());

        switch (state) {
            case SUCCEEDED -> {
                if (status.receivedSats() == null || status.receivedSats() <= 0L) {
                    return;
                }
                long received = status.receivedSats();
                if (request.getAmountSats() != null && received < request.getAmountSats()) {
                    log.warn(
                            "[KFE LN PR Monitor] underpaid invoice publicId={} expected={} received={}",
                            request.getPublicId(),
                            request.getAmountSats(),
                            received);
                    return;
                }
                self.settleSettledInvoice(request.getId(), received, status.rawPayload());
            }
            case IN_FLIGHT -> {
                // HTLC routed, not yet settled. Leave open for next cycle.
                log.debug(
                        "[KFE LN PR Monitor] in-flight invoice publicId={} paymentHash={}",
                        request.getPublicId(),
                        request.getPaymentHash());
            }
            case FAILED -> {
                log.warn(
                        "[KFE LN PR Monitor] failed invoice publicId={} paymentHash={} status={}",
                        request.getPublicId(),
                        request.getPaymentHash(),
                        status.status());
                self.markFailed(request.getId(), status.status(), status.rawPayload());
            }
            case UNKNOWN -> {
                log.warn(
                        "[KFE LN PR Monitor] unknown result invoice publicId={} paymentHash={} status={} — "
                                + "blocking until reconciled",
                        request.getPublicId(),
                        request.getPaymentHash(),
                        status.status());
                auditLogService.record(
                        "KFE_LN_PAYMENT_UNKNOWN",
                        request.getId(),
                        request.getWalletId(),
                        null,
                        null,
                        Map.of(
                                "paymentRequestId", request.getId().toString(),
                                "publicId", request.getPublicId(),
                                "paymentHash", request.getPaymentHash(),
                                "rawStatus", status.status()));
            }
        }
    }

    /**
     * Classify a raw LND status string into a deterministic payment state (ITEM 22).
     */
    static LightningPaymentState classifyPaymentState(String status) {
        if (status == null || status.isBlank()) {
            return LightningPaymentState.UNKNOWN;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SETTLED", "PAID", "COMPLETE", "COMPLETED", "CONFIRMED" ->
                LightningPaymentState.SUCCEEDED;
            case "IN_FLIGHT", "SUBMITTED", "PENDING", "PROCESSING", "ROUTING" ->
                LightningPaymentState.IN_FLIGHT;
            case "FAILED", "CANCELED", "CANCELLED", "REJECTED", "EXPIRED" ->
                LightningPaymentState.FAILED;
            default -> LightningPaymentState.UNKNOWN;
        };
    }

    // ------------------------------------------------------------------ //
    //  Transactional settlement methods                                  //
    // ------------------------------------------------------------------ //

    @Transactional
    public void markFailed(UUID paymentRequestId, String rawStatus, String rawPayload) {
        KfePaymentRequestEntity request = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElse(null);
        if (request == null || request.getStatus() != KfePaymentRequestStatus.OPEN) {
            return;
        }
        request.markFailed(rawStatus);
        paymentRequestRepository.save(request);
        auditLogService.record(
                "KFE_PAYMENT_REQUEST_FAILED",
                request.getId(),
                request.getWalletId(),
                null,
                null,
                Map.of(
                        "paymentRequestId", request.getId().toString(),
                        "publicId", request.getPublicId(),
                        "paymentHash", request.getPaymentHash(),
                        "rawStatus", rawStatus));
    }

    @Transactional
    public void expireRequest(UUID paymentRequestId) {
        KfePaymentRequestEntity request = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElse(null);
        if (request == null || request.getStatus() != KfePaymentRequestStatus.OPEN) {
            return;
        }
        if (request.isExpired(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))) {
            request.expire();
            paymentRequestRepository.save(request);
        }
    }

    @Transactional
    public void settleSettledInvoice(UUID paymentRequestId, long receivedSats, String rawPayload) {
        KfePaymentRequestEntity request = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElse(null);
        if (request == null || request.getStatus() != KfePaymentRequestStatus.OPEN) {
            return;
        }
        settleLightningPaymentRequest(request, receivedSats, rawPayload);
        if (notificationPort != null) {
            try {
                notificationPort.notifyPaymentRequestDepositConfirmed(
                        request.getUserId(),
                        request.getPaidTransactionId(),
                        request.getId(),
                        request.getPublicId(),
                        request.getWalletId(),
                        "LIGHTNING",
                        receivedSats);
            } catch (RuntimeException exception) {
                log.warn("[KFE LN PR Monitor] deposit confirmed notification failed publicId={}: {}",
                        request.getPublicId(), exception.getMessage());
            }
        }
        if (webhookDeliveryService != null) {
            webhookDeliveryService.publishAfterCommit(
                    request.getWebhookUrl(),
                    webhookDeliveryService.buildPayload(
                            KfeWebhookEvent.PAYMENT_SETTLED,
                            request.getPublicId(),
                            receivedSats,
                            KfePaymentRequestStatus.PAID.name()));
        }
    }

    /**
     * Credits a payment request that was found settled on LND despite KFE marking it expired.
     * This reconciliation path prevents orphaned payments when LND settles near the deadline
     * and the monitor hasn't observed it before the KFE expiry clock ticked.
     */
    @Transactional
    public void reconcileExpiredButSettled(UUID paymentRequestId, long receivedSats, String rawPayload) {
        KfePaymentRequestEntity request = paymentRequestRepository.findByIdForUpdate(paymentRequestId)
                .orElse(null);
        if (request == null) {
            return;
        }
        // If another thread settled it while we were probing, avoid duplicate.
        if (request.getStatus() == KfePaymentRequestStatus.PAID) {
            return;
        }
        // Accept OPEN or EXPIRED — the invoice was settled, credit must land.
        if (request.getStatus() != KfePaymentRequestStatus.OPEN
                && request.getStatus() != KfePaymentRequestStatus.EXPIRED) {
            return;
        }

        log.warn(
                "[KFE LN PR Monitor] reconciling expired-but-settled PR publicId={} received={}sats — "
                        + "LND settled but KFE clock had expired",
                request.getPublicId(),
                receivedSats);

        settleLightningPaymentRequest(request, receivedSats, rawPayload);

        auditLogService.record(
                "KFE_PAYMENT_REQUEST_RECONCILED",
                request.getPaidTransactionId(),
                request.getWalletId(),
                null,
                KfeTransactionStatus.SETTLED,
                Map.of(
                        "paymentRequestId", request.getId().toString(),
                        "publicId", request.getPublicId(),
                        "rail", "LIGHTNING",
                        "paymentHash", request.getPaymentHash(),
                        "receivedSats", receivedSats,
                        "note", "invoice settled before expiry but KFE clock had already expired"));

        if (notificationPort != null) {
            try {
                notificationPort.notifyPaymentRequestDepositConfirmed(
                        request.getUserId(),
                        request.getPaidTransactionId(),
                        request.getId(),
                        request.getPublicId(),
                        request.getWalletId(),
                        "LIGHTNING",
                        receivedSats);
            } catch (RuntimeException exception) {
                log.warn("[KFE LN PR Monitor] reconcile notification failed publicId={}: {}",
                        request.getPublicId(), exception.getMessage());
            }
        }

        if (webhookDeliveryService != null) {
            webhookDeliveryService.publishAfterCommit(
                    request.getWebhookUrl(),
                    webhookDeliveryService.buildPayload(
                            KfeWebhookEvent.PAYMENT_RECONCILED,
                            request.getPublicId(),
                            receivedSats,
                            KfePaymentRequestStatus.PAID.name()));
        }
    }

    /** Kept for tests that call inspect by id; production path uses {@link #probeAndMaybeSettle}. */
    @Deprecated
    @Transactional
    public void inspect(UUID paymentRequestId) {
        KfePaymentRequestEntity request = paymentRequestRepository.findById(paymentRequestId).orElse(null);
        if (request == null) {
            return;
        }
        // Re-load outside lock path via probe (LND) then settle via self if needed.
        // For test callers expecting single-method behavior with TX: probe LND then settle.
        if (request.getPaymentHash() == null || request.getPaymentHash().isBlank()) {
            return;
        }
        if (request.isExpired(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))) {
            expireRequest(paymentRequestId);
            return;
        }
        CustodyGateway.IncomingLightningInvoiceStatus status = lightningInvoiceGateway.getLightningInvoiceStatus(
                new CustodyGateway.LightningInvoiceStatusCommand(
                        request.getUserId(),
                        null,
                        null,
                        request.getPaymentHash(),
                        request.getProviderReference(),
                        request.getPaymentRequest()));
        LightningPaymentState state = classifyPaymentState(status.status());
        if (state != LightningPaymentState.SUCCEEDED
                || status.receivedSats() == null || status.receivedSats() <= 0L) {
            return;
        }
        long received = status.receivedSats();
        if (request.getAmountSats() != null && received < request.getAmountSats()) {
            return;
        }
        // Still inside TX here (legacy inspect) — prefer settleSettledInvoice for production path.
        settleLightningPaymentRequest(
                paymentRequestRepository.findByIdForUpdate(paymentRequestId).orElse(request),
                received,
                status.rawPayload());
    }

    private void settleLightningPaymentRequest(
            KfePaymentRequestEntity request,
            long receivedSats,
            String rawPayload) {
        String idempotencyKey = "payment-request:" + request.getId() + ":" + request.getPaymentHash();
        if (transactionRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            // Already settled path — ensure PR marked paid.
            transactionRepository.findByIdempotencyKey(idempotencyKey).ifPresent(tx -> {
                if (request.getStatus() != KfePaymentRequestStatus.PAID) {
                    request.markPaid(tx.getId());
                    paymentRequestRepository.save(request);
                }
            });
            return;
        }

        KfePricingService.Quote quote = pricingService.quote(
                KfeRail.LIGHTNING,
                KfeDirection.INBOUND,
                receivedSats,
                0L);

        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(request.getUserId());
        tx.setIdempotencyKey(idempotencyKey);
        tx.setRail(KfeRail.LIGHTNING);
        tx.setDirection(KfeDirection.INBOUND);
        tx.setDestinationWalletId(request.getWalletId());
        tx.setExternalReference(request.getPublicId());
        tx.setGrossAmountSats(quote.grossAmountSats());
        tx.setReceiverAmountSats(quote.receiverAmountSats());
        tx.setNetworkFeeSats(quote.networkFeeSats());
        tx.setKeroseneFeeSats(quote.keroseneFeeSats());
        tx.setTotalDebitSats(quote.totalDebitSats());
        tx.setProvider(lightningInvoiceGateway.providerName());
        tx.setProviderReference(request.getProviderReference());
        tx.setPaymentHash(request.getPaymentHash());
        // Instant settlement — do not mimic on-chain block confirmations.
        tx.setConfirmations(0);
        tx.setStatus(KfeTransactionStatus.SETTLED);
        tx = transactionRepository.save(tx);

        balanceService.creditAvailable(request.getWalletId(), ASSET_BTC, quote.receiverAmountSats());
        movementRecorder.record(
                tx.getId(),
                request.getWalletId(),
                KfeLedgerMovementTypes.CREDIT_PAYMENT_REQUEST,
                quote.receiverAmountSats(),
                null,
                "AVAILABLE");
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
                        "rail", "LIGHTNING",
                        "paymentHash", request.getPaymentHash(),
                        "receivedSats", receivedSats,
                        "creditedSats", quote.receiverAmountSats()));
        java.util.Map<String, Object> statementPayload =
                new java.util.LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, request.getUserId()));
        statementPayload.put("paymentRequestId", request.getId().toString());
        statementPayload.put("publicId", request.getPublicId());
        statementPayload.put(
                "rawPayloadHash", Integer.toHexString(rawPayload != null ? rawPayload.hashCode() : 0));
        statementService.recordUserStatement(
                request.getUserId(),
                request.getWalletId(),
                tx,
                statementPayload);
        dashboardPublisher.publishAfterCommit(request.getUserId());
        log.info(
                "[KFE LN PR Monitor] settled lightning PR publicId={} credited={}sats",
                request.getPublicId(),
                quote.receiverAmountSats());
    }

    private boolean isSettled(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SETTLED", "PAID", "SUCCEEDED", "COMPLETE", "COMPLETED", "CONFIRMED" -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ //
    //  Stream cursor accessors                                           //
    // ------------------------------------------------------------------ //

    public long getLastAddIndex() {
        return lastAddIndex.get();
    }

    public long getLastSettleIndex() {
        return lastSettleIndex.get();
    }
}
