package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.dto.KfeTransactionResponse;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cancels open invoices / payment links and pre-settlement transactions so they do not linger
 * as PENDING forever in history.
 */
@Service
public class KfeTransactionCancellationService {

    private static final Logger log = LoggerFactory.getLogger(KfeTransactionCancellationService.class);
    public static final String FAILURE_USER_CANCELLED = "USER_CANCELLED";
    public static final String CANCEL_TARGET_PAYMENT_REQUEST = "PAYMENT_REQUEST";
    public static final String CANCEL_TARGET_TRANSACTION = "TRANSACTION";
    private static final String ASSET_BTC = "BTC";

    private final KfeTransactionRepository transactionRepository;
    private final KfePaymentRequestRepository paymentRequestRepository;
    private final KfeBalanceService balanceService;
    private final KfeLightningLiquidityService lightningLiquidityService;
    private final KfeStatementService statementService;
    private final KfeResponseMapper responseMapper;
    private final KfeDashboardPublisher dashboardPublisher;
    private final KfeAuditLogService auditLogService;
    private final LightningInvoiceGateway lightningInvoiceGateway;

    public KfeTransactionCancellationService(
            KfeTransactionRepository transactionRepository,
            KfePaymentRequestRepository paymentRequestRepository,
            KfeBalanceService balanceService,
            KfeLightningLiquidityService lightningLiquidityService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            KfeAuditLogService auditLogService,
            @Qualifier("kfeExternalLightningInvoiceGateway")
            LightningInvoiceGateway lightningInvoiceGateway) {
        this.transactionRepository = transactionRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.balanceService = balanceService;
        this.lightningLiquidityService = lightningLiquidityService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.auditLogService = auditLogService;
        this.lightningInvoiceGateway = lightningInvoiceGateway;
    }

    public CancellationHints hintsFor(KfeTransactionEntity tx, Long userId) {
        if (tx == null || userId == null) {
            return CancellationHints.none();
        }
        Optional<KfePaymentRequestEntity> linkedPr = findLinkedPaymentRequest(tx, userId);
        if (linkedPr.isPresent()) {
            KfePaymentRequestEntity pr = linkedPr.get();
            boolean prCancellable = isPaymentRequestCancellable(pr);
            if (prCancellable) {
                return new CancellationHints(
                        true,
                        CANCEL_TARGET_PAYMENT_REQUEST,
                        pr.getId(),
                        pr.getPublicId(),
                        pr.getStatus() != null ? pr.getStatus().name() : null);
            }
            return new CancellationHints(
                    false,
                    null,
                    pr.getId(),
                    pr.getPublicId(),
                    pr.getStatus() != null ? pr.getStatus().name() : null);
        }
        if (isTransactionCancellable(tx)) {
            return new CancellationHints(true, CANCEL_TARGET_TRANSACTION, null, null, null);
        }
        return CancellationHints.none();
    }

    @Transactional
    public KfeTransactionResponse cancelTransaction(Long userId, UUID transactionId) {
        KfeTransactionEntity tx = transactionRepository
                .findParticipantVisibleById(transactionId, userId, KfeRail.INTERNAL, KfeDirection.INTERNAL)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));

        CancellationHints hints = hintsFor(tx, userId);
        if (!hints.cancellable()) {
            throw new IllegalStateException(
                    "Esta transação não pode ser cancelada (já liquidada, em execução na rede, ou sem invoice aberta).");
        }

        if (CANCEL_TARGET_PAYMENT_REQUEST.equals(hints.cancelTarget()) && hints.paymentRequestId() != null) {
            cancelPaymentRequestInternal(userId, hints.paymentRequestId(), true);
            // Reload tx — may have been failed by PR cancel side-effects.
            tx = transactionRepository.findById(transactionId).orElse(tx);
            if (isTransactionCancellable(tx) || isIncomplete(tx)) {
                failTransaction(tx, "Invoice/link de pagamento cancelado pelo usuário.");
            }
        } else {
            failTransaction(tx, "Cancelado pelo usuário.");
            // If this outbound referenced a payment request public id, cancel that too.
            findLinkedPaymentRequest(tx, userId).ifPresent(pr -> {
                if (isPaymentRequestCancellable(pr)) {
                    cancelPaymentRequestInternal(userId, pr.getId(), false);
                }
            });
        }

        dashboardPublisher.publishAfterCommit(userId);
        return responseMapper.toTransactionResponse(
                transactionRepository.findById(transactionId).orElse(tx),
                userId);
    }

    @Transactional
    public KfePaymentRequestEntity cancelPaymentRequest(Long userId, UUID paymentRequestId) {
        return cancelPaymentRequestInternal(userId, paymentRequestId, true);
    }

    private KfePaymentRequestEntity cancelPaymentRequestInternal(
            Long userId, UUID paymentRequestId, boolean publishDashboard) {
        KfePaymentRequestEntity paymentRequest = paymentRequestRepository
                .findByIdAndUserId(paymentRequestId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));

        if (!isPaymentRequestCancellable(paymentRequest)) {
            // Idempotent: already cancelled/hidden/paid — just return current state.
            return paymentRequest;
        }

        tryCancelLightningInvoice(paymentRequest);

        KfePaymentRequestStatus previous = paymentRequest.getStatus();
        paymentRequest.cancel();
        paymentRequest = paymentRequestRepository.save(paymentRequest);

        auditLogService.record(
                "KFE_PAYMENT_REQUEST_CANCELLED",
                null,
                paymentRequest.getWalletId(),
                null,
                null,
                Map.of(
                        "paymentRequestId", paymentRequest.getId().toString(),
                        "publicId", paymentRequest.getPublicId(),
                        "previousStatus", previous != null ? previous.name() : "",
                        "rail", paymentRequest.getRail() != null ? paymentRequest.getRail().name() : ""));

        // Close any incomplete txs that were created while observing this invoice/link.
        for (KfeTransactionEntity related : findRelatedTransactions(paymentRequest)) {
            if (isIncomplete(related)) {
                failTransaction(related, "Invoice/link de pagamento cancelado pelo usuário.");
            }
        }

        if (publishDashboard) {
            dashboardPublisher.publishAfterCommit(userId);
        }
        return paymentRequest;
    }

    private void failTransaction(KfeTransactionEntity tx, String message) {
        if (tx == null || tx.getId() == null) {
            return;
        }
        KfeTransactionStatus previous = tx.getStatus();
        if (previous == KfeTransactionStatus.SETTLED || previous == KfeTransactionStatus.FAILED) {
            return;
        }

        // Release locked funds if the user reserved but never settled.
        if ((previous == KfeTransactionStatus.LOCKED || previous == KfeTransactionStatus.EXECUTING)
                && tx.getSourceWalletId() != null
                && tx.getTotalDebitSats() > 0L) {
            try {
                balanceService.releaseReserved(tx.getSourceWalletId(), ASSET_BTC, tx.getTotalDebitSats());
            } catch (RuntimeException exception) {
                log.warn(
                        "KFE cancel could not release reserve txId={} walletId={}: {}",
                        tx.getId(),
                        tx.getSourceWalletId(),
                        exception.getMessage());
            }
        }
        if (tx.getRail() == KfeRail.LIGHTNING && tx.getDirection() == KfeDirection.OUTBOUND) {
            try {
                lightningLiquidityService.releaseForTransaction(tx.getId());
            } catch (RuntimeException exception) {
                log.debug("KFE cancel liquidity release skipped txId={}: {}", tx.getId(), exception.getMessage());
            }
        }

        tx.setStatus(KfeTransactionStatus.FAILED);
        tx.setFailureCode(FAILURE_USER_CANCELLED);
        tx.setFailureMessage(trim(message, 255));
        transactionRepository.save(tx);

        Map<String, Object> payload = new LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, tx.getUserId()));
        payload.put("cancelled", true);
        statementService.recordUserStatement(
                tx.getUserId(),
                tx.getSourceWalletId() != null ? tx.getSourceWalletId() : tx.getDestinationWalletId(),
                tx,
                payload);

        auditLogService.record(
                "KFE_TRANSACTION_CANCELLED",
                tx.getId(),
                tx.getSourceWalletId() != null ? tx.getSourceWalletId() : tx.getDestinationWalletId(),
                previous,
                KfeTransactionStatus.FAILED,
                Map.of(
                        "failureCode", FAILURE_USER_CANCELLED,
                        "rail", tx.getRail() != null ? tx.getRail().name() : "",
                        "direction", tx.getDirection() != null ? tx.getDirection().name() : ""));
    }

    private void tryCancelLightningInvoice(KfePaymentRequestEntity paymentRequest) {
        if (paymentRequest.getRail() != KfeRail.LIGHTNING) {
            return;
        }
        String hash = paymentRequest.getPaymentHash();
        if ((hash == null || hash.isBlank())
                && (paymentRequest.getPaymentRequest() == null || paymentRequest.getPaymentRequest().isBlank())) {
            return;
        }
        try {
            boolean cancelled = lightningInvoiceGateway.cancelLightningInvoice(
                    new CustodyGateway.LightningInvoiceCancellationCommand(
                            paymentRequest.getUserId(),
                            null,
                            null,
                            hash,
                            paymentRequest.getProviderReference(),
                            paymentRequest.getPaymentRequest()));
            log.info(
                    "KFE LN invoice cancel paymentRequestId={} hash={} result={}",
                    paymentRequest.getId(),
                    hash,
                    cancelled);
        } catch (RuntimeException exception) {
            // Best-effort: local cancel still proceeds so history stops hanging as OPEN/PENDING.
            log.warn(
                    "KFE LN invoice cancel best-effort failed paymentRequestId={}: {}",
                    paymentRequest.getId(),
                    exception.getMessage());
        }
    }

    private Optional<KfePaymentRequestEntity> findLinkedPaymentRequest(KfeTransactionEntity tx, Long userId) {
        if (tx.getId() != null) {
            Optional<KfePaymentRequestEntity> byPaid =
                    paymentRequestRepository.findByPaidTransactionIdAndUserId(tx.getId(), userId);
            if (byPaid.isPresent()) {
                return byPaid;
            }
        }
        String ref = tx.getExternalReference();
        if (ref != null && !ref.isBlank()) {
            Optional<KfePaymentRequestEntity> byPublic =
                    paymentRequestRepository.findByPublicId(ref.trim());
            if (byPublic.isPresent() && userId.equals(byPublic.get().getUserId())) {
                return byPublic;
            }
        }
        // Observed inbound: idempotency payment-request:{uuid}:{txid}
        String key = tx.getIdempotencyKey();
        if (key != null && key.startsWith("payment-request:")) {
            String[] parts = key.split(":");
            if (parts.length >= 2) {
                try {
                    UUID prId = UUID.fromString(parts[1]);
                    return paymentRequestRepository.findByIdAndUserId(prId, userId);
                } catch (IllegalArgumentException ignored) {
                    // not a uuid
                }
            }
        }
        return Optional.empty();
    }

    private List<KfeTransactionEntity> findRelatedTransactions(KfePaymentRequestEntity pr) {
        List<KfeTransactionEntity> out = new ArrayList<>();
        if (pr.getPaidTransactionId() != null) {
            transactionRepository.findById(pr.getPaidTransactionId()).ifPresent(out::add);
        }
        transactionRepository
                .findTopByIdempotencyKeyStartingWithOrderByCreatedAtDesc(
                        "payment-request:" + pr.getId() + ":")
                .ifPresent(tx -> {
                    if (out.stream().noneMatch(existing -> existing.getId().equals(tx.getId()))) {
                        out.add(tx);
                    }
                });
        if (pr.getPublicId() != null && !pr.getPublicId().isBlank()) {
            // External reference may equal public id for pays against the link.
            // Limit to same user inbound to avoid broad scans — use repository if available.
            transactionRepository.findTop200ByUserIdOrderByCreatedAtDesc(pr.getUserId()).stream()
                    .filter(tx -> pr.getPublicId().equals(tx.getExternalReference()))
                    .filter(tx -> out.stream().noneMatch(e -> e.getId().equals(tx.getId())))
                    .limit(5)
                    .forEach(out::add);
        }
        return out;
    }

    static boolean isPaymentRequestCancellable(KfePaymentRequestEntity pr) {
        if (pr == null || pr.getStatus() == null) {
            return false;
        }
        return pr.getStatus() == KfePaymentRequestStatus.OPEN
                || pr.getStatus() == KfePaymentRequestStatus.EXPIRED;
    }

    /**
     * Pre-settlement txs the user may abandon. Does not cancel confirmed chain settlements.
     */
    static boolean isTransactionCancellable(KfeTransactionEntity tx) {
        if (tx == null || tx.getStatus() == null) {
            return false;
        }
        return switch (tx.getStatus()) {
            case INTENT, VALIDATING, QUORUM_SYNC, LOCKED -> true;
            // EXECUTING on-chain after broadcast: only allow if no blockchain txid yet.
            case EXECUTING -> tx.getBlockchainTxid() == null || tx.getBlockchainTxid().isBlank();
            case SETTLED, FAILED, CANCELLED, REQUIRES_RECONCILIATION, CONFLICTED,
                 CONFLICTED_RECONCILING, CONFLICTED_REFUNDED, REORG_RECONCILIATION,
                 BROADCAST, CONFIRMING, DROPPED, ABANDONED -> false;
        };
    }

    static boolean isIncomplete(KfeTransactionEntity tx) {
        if (tx == null || tx.getStatus() == null) {
            return false;
        }
        return tx.getStatus() != KfeTransactionStatus.SETTLED
                && tx.getStatus() != KfeTransactionStatus.FAILED
                && tx.getStatus() != KfeTransactionStatus.CANCELLED
                && tx.getStatus() != KfeTransactionStatus.CONFLICTED
                && tx.getStatus() != KfeTransactionStatus.CONFLICTED_REFUNDED
                && tx.getStatus() != KfeTransactionStatus.DROPPED
                && tx.getStatus() != KfeTransactionStatus.ABANDONED;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    public record CancellationHints(
            boolean cancellable,
            String cancelTarget,
            UUID paymentRequestId,
            String paymentRequestPublicId,
            String paymentRequestStatus) {

        static CancellationHints none() {
            return new CancellationHints(false, null, null, null, null);
        }
    }
}
