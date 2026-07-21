package source.kfe.service;

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
import source.kfe.application.transaction.KfeBalanceMovementRecorder;
import source.kfe.application.transaction.KfeLedgerMovementTypes;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfePaymentRequestEntity;
import source.kfe.model.KfePaymentRequestStatus;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.rail.CustodyGateway;
import source.kfe.rail.LightningInvoiceGateway;
import source.kfe.repository.KfePaymentRequestRepository;
import source.kfe.repository.KfeTransactionRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Polls OPEN Lightning payment requests and credits destination wallets when invoices settle.
 */
@Service
public class KfePaymentRequestLightningMonitor {

    private static final Logger log = LoggerFactory.getLogger(KfePaymentRequestLightningMonitor.class);
    private static final String ASSET_BTC = "BTC";

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
            @Lazy KfePaymentRequestLightningMonitor self) {
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
    }

    @Scheduled(
            fixedDelayString = "${kfe.payment-request-lightning-monitor.fixed-delay-ms:1000}",
            initialDelayString = "${kfe.payment-request-lightning-monitor.initial-delay-ms:2000}")
    public void reconcileOpenLightningPaymentRequests() {
        if (!enabled || !lightningInvoiceGateway.isLive()) {
            return;
        }
        // Read-only list — no row lock. LND is probed outside any DB transaction.
        List<KfePaymentRequestEntity> requests = paymentRequestRepository.findByStatusAndRailOrderByCreatedAtAsc(
                KfePaymentRequestStatus.OPEN,
                KfeRail.LIGHTNING,
                PageRequest.of(0, batchSize));
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
    }

    /**
     * Probe LND without holding a DB connection, then settle in a short transactional method
     * via Spring proxy ({@code self}) so {@code @Transactional} actually applies.
     */
    void probeAndMaybeSettle(KfePaymentRequestEntity request) {
        if (request == null || request.getStatus() != KfePaymentRequestStatus.OPEN) {
            return;
        }
        if (request.getPaymentHash() == null || request.getPaymentHash().isBlank()) {
            return;
        }
        if (request.isExpired(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))) {
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
        if (!isSettled(status.status()) || status.receivedSats() == null || status.receivedSats() <= 0L) {
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
        if (!isSettled(status.status()) || status.receivedSats() == null || status.receivedSats() <= 0L) {
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
}
