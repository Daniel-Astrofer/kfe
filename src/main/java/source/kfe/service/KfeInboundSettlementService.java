package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.common.financial.FinancialNotificationPort;
import source.kfe.model.KfeBalanceMovementEntity;
import source.kfe.model.KfeExecutionOutboxEntity;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.repository.KfeBalanceMovementRepository;
import source.kfe.repository.KfeExecutionOutboxRepository;
import source.kfe.repository.KfeIdempotencyRepository;
import source.kfe.repository.KfeTransactionRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class KfeInboundSettlementService {

    private static final Logger log = LoggerFactory.getLogger(KfeInboundSettlementService.class);
    private static final String ASSET_BTC = "BTC";

    private final KfeTransactionRepository transactionRepository;
    private final KfeExecutionOutboxRepository outboxRepository;
    private final KfeBalanceMovementRepository movementRepository;
    private final KfeIdempotencyRepository idempotencyRepository;
    private final KfeBalanceService balanceService;
    private final KfeAuditLogService auditLogService;
    private final KfeStatementService statementService;
    private final KfeDashboardPublisher dashboardPublisher;
    private final KfeHashService hashService;
    private final FinancialNotificationPort notificationPort;
    private final KfeFeeSettlementService feeSettlementService;

    public KfeInboundSettlementService(
            KfeTransactionRepository transactionRepository,
            KfeExecutionOutboxRepository outboxRepository,
            KfeBalanceMovementRepository movementRepository,
            KfeIdempotencyRepository idempotencyRepository,
            KfeBalanceService balanceService,
            KfeAuditLogService auditLogService,
            KfeStatementService statementService,
            KfeDashboardPublisher dashboardPublisher,
            KfeHashService hashService,
            FinancialNotificationPort notificationPort,
            KfeFeeSettlementService feeSettlementService) {
        this.transactionRepository = transactionRepository;
        this.outboxRepository = outboxRepository;
        this.movementRepository = movementRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
        this.statementService = statementService;
        this.dashboardPublisher = dashboardPublisher;
        this.hashService = hashService;
        this.notificationPort = notificationPort;
        this.feeSettlementService = feeSettlementService;
    }

    @Transactional
    public boolean settle(InboundSettlementProof proof) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(proof.outboxId()).orElse(null);
        if (outbox == null) {
            return false;
        }

        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(proof.transactionId()).orElse(null);
        if (tx == null) {
            markOutboxFailed(outbox, "TRANSACTION_NOT_FOUND", "KFE inbound transaction does not exist.");
            return false;
        }

        if (tx.getStatus() == KfeTransactionStatus.SETTLED) {
            markOutboxDispatched(outbox, proof.providerReference());
            return true;
        }
        if (hasSettledProviderReference(tx, proof)) {
            markOutboxDispatched(outbox, proof.providerReference());
            return true;
        }
        if (tx.getStatus() != KfeTransactionStatus.REQUIRES_RECONCILIATION
                && tx.getStatus() != KfeTransactionStatus.EXECUTING) {
            return false;
        }
        if (tx.getDestinationWalletId() == null || proof.observedAmountSats() <= 0L) {
            return false;
        }
        if (proof.observedAmountSats() < tx.getGrossAmountSats()) {
            markStillReconciling(outbox, tx, "INBOUND_AMOUNT_BELOW_EXPECTED");
            return false;
        }

        long creditSats = tx.getReceiverAmountSats() > 0L
                ? tx.getReceiverAmountSats()
                : proof.observedAmountSats();
        if (creditSats <= 0L) {
            return false;
        }

        balanceService.creditAvailable(tx.getDestinationWalletId(), ASSET_BTC, creditSats);
        movement(tx.getId(), tx.getDestinationWalletId(), "CREDIT_INBOUND", creditSats, null, "AVAILABLE");

        KfeTransactionStatus previous = tx.getStatus();
        tx.setProvider(trim(proof.provider(), 64));
        tx.setProviderReference(trim(proof.providerReference(), 255));
        if (tx.getRail() == KfeRail.ONCHAIN) {
            tx.setBlockchainTxid(trim(proof.networkReference(), 128));
        } else if (tx.getRail() == KfeRail.LIGHTNING) {
            tx.setPaymentHash(trim(proof.networkReference(), 128));
        }
        tx.setConfirmations(Math.max(tx.getConfirmations(), proof.confirmations()));
        tx.setFailureCode(null);
        tx.setFailureMessage(null);
        tx.setStatus(KfeTransactionStatus.SETTLED);
        transactionRepository.save(tx);
        feeSettlementService.creditKeroseneFee(tx);

        auditLogService.record(
                "KFE_INBOUND_SETTLED",
                tx.getId(),
                tx.getDestinationWalletId(),
                previous,
                KfeTransactionStatus.SETTLED,
                Map.of(
                        "provider", firstNonBlank(proof.provider(), "UNKNOWN"),
                        "providerReferenceHash", hashService.sha256(firstNonBlank(proof.providerReference(), "")),
                        "networkReferenceHash", hashService.sha256(firstNonBlank(proof.networkReference(), "")),
                        "observedAmountSats", proof.observedAmountSats(),
                        "creditedSats", creditSats,
                        "confirmations", proof.confirmations()));
        recordStatement(tx, proof.rawPayload());
        notifyInboundDepositCredited(tx, creditSats);
        updateIdempotency(tx);
        markOutboxDispatched(outbox, proof.providerReference());
        dashboardPublisher.publishAfterCommit(tx.getUserId());
        return true;
    }

    private boolean hasSettledProviderReference(KfeTransactionEntity tx, InboundSettlementProof proof) {
        String providerReference = trim(proof.providerReference(), 255);
        if (providerReference == null || providerReference.isBlank()) {
            return false;
        }
        return transactionRepository.findByProviderReferenceAndStatusForUpdate(
                        providerReference,
                        KfeTransactionStatus.SETTLED)
                .stream()
                .anyMatch(existing -> !existing.getId().equals(tx.getId()));
    }

    private void markStillReconciling(
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            String code) {
        tx.setFailureCode(code);
        tx.setFailureMessage("Trusted monitor observed less than the expected inbound amount.");
        transactionRepository.save(tx);
        outbox.setLastError(code + ": trusted monitor observed less than expected.");
        outboxRepository.save(outbox);
    }

    private void markOutboxFailed(KfeExecutionOutboxEntity outbox, String code, String message) {
        outbox.setStatus("FAILED_FINAL");
        outbox.setLastError(trim(code + ": " + message, 1000));
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    private void markOutboxDispatched(KfeExecutionOutboxEntity outbox, String providerReference) {
        outbox.setStatus("DISPATCHED");
        outbox.setProviderReference(trim(providerReference, 255));
        outbox.setDispatchedAt(LocalDateTime.now());
        outbox.setLastError(null);
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    private void movement(
            UUID transactionId,
            UUID walletId,
            String movementType,
            long amountSats,
            String fromBucket,
            String toBucket) {
        KfeBalanceMovementEntity movement = new KfeBalanceMovementEntity();
        movement.setTransactionId(transactionId);
        movement.setWalletId(walletId);
        movement.setMovementType(movementType);
        movement.setAmountSats(amountSats);
        movement.setFromBucket(fromBucket);
        movement.setToBucket(toBucket);
        movementRepository.save(movement);
    }

    private void recordStatement(KfeTransactionEntity tx, String providerPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", tx.getId().toString());
        payload.put("status", tx.getStatus().name());
        payload.put("rail", tx.getRail().name());
        payload.put("direction", tx.getDirection().name());
        payload.put("grossAmountSats", tx.getGrossAmountSats());
        payload.put("receiverAmountSats", tx.getReceiverAmountSats());
        payload.put("networkFeeSats", tx.getNetworkFeeSats());
        payload.put("keroseneFeeSats", tx.getKeroseneFeeSats());
        payload.put("provider", tx.getProvider());
        payload.put("providerReferenceHash", hashService.sha256(firstNonBlank(tx.getProviderReference(), "")));
        payload.put("blockchainTxid", tx.getBlockchainTxid());
        payload.put("paymentHash", tx.getPaymentHash());
        if (providerPayload != null && !providerPayload.isBlank()) {
            payload.put("providerPayloadHash", hashService.sha256(providerPayload));
        }
        statementService.recordUserStatement(tx.getUserId(), tx.getDestinationWalletId(), tx, payload);
    }

    private void notifyInboundDepositCredited(KfeTransactionEntity tx, long creditSats) {
        try {
            notificationPort.notifyDepositConfirmed(
                    tx.getUserId(),
                    tx.getId(),
                    tx.getDestinationWalletId(),
                    tx.getRail().name(),
                    creditSats,
                    tx.getConfirmations());
        } catch (RuntimeException exception) {
            log.warn(
                    "KFE inbound deposit was credited but notification failed. transactionId={} error={}",
                    tx.getId(),
                    exception.getMessage());
        }
    }

    private void updateIdempotency(KfeTransactionEntity tx) {
        idempotencyRepository.findById(new source.kfe.model.KfeIdempotencyId(
                        tx.getUserId(),
                        tx.getIdempotencyKey()))
                .ifPresent(entity -> {
                    entity.setStatus(tx.getStatus().name());
                    idempotencyRepository.save(entity);
                });
    }

    private void clearClaim(KfeExecutionOutboxEntity outbox) {
        outbox.setClaimedBy(null);
        outbox.setClaimedAt(null);
    }

    private String firstNonBlank(String... values) {
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

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record InboundSettlementProof(
            UUID transactionId,
            UUID outboxId,
            String provider,
            String providerReference,
            String networkReference,
            long observedAmountSats,
            int confirmations,
            String rawPayload) {
    }
}
