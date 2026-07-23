package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.common.financial.FinancialNotificationPort;
import com.kerosene.kfe.application.transaction.KfeLedgerMovementTypes;
import com.kerosene.kfe.model.KfeBalanceMovementEntity;
import com.kerosene.kfe.model.KfeExecutionOutboxEntity;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.repository.KfeBalanceMovementRepository;
import com.kerosene.kfe.repository.KfeExecutionOutboxRepository;
import com.kerosene.kfe.repository.KfeIdempotencyRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    private final KfeWalletRepository walletRepository;
    private final KfeBalanceService balanceService;
    private final KfeAuditLogService auditLogService;
    private final KfeStatementService statementService;
    private final KfeResponseMapper responseMapper;
    private final KfeDashboardPublisher dashboardPublisher;
    private final KfeHashService hashService;
    private final FinancialNotificationPort notificationPort;
    private final KfeFeeSettlementService feeSettlementService;
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService;
    private final ObjectProvider<KfeBalanceMetrics> balanceMetrics;

    public KfeInboundSettlementService(
            KfeTransactionRepository transactionRepository,
            KfeExecutionOutboxRepository outboxRepository,
            KfeBalanceMovementRepository movementRepository,
            KfeIdempotencyRepository idempotencyRepository,
            KfeWalletRepository walletRepository,
            KfeBalanceService balanceService,
            KfeAuditLogService auditLogService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            KfeHashService hashService,
            FinancialNotificationPort notificationPort,
            KfeFeeSettlementService feeSettlementService,
            ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics) {
        this.transactionRepository = transactionRepository;
        this.outboxRepository = outboxRepository;
        this.movementRepository = movementRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.walletRepository = walletRepository;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.hashService = hashService;
        this.notificationPort = notificationPort;
        this.feeSettlementService = feeSettlementService;
        this.onchainBalanceSyncService = onchainBalanceSyncService;
        this.balanceMetrics = balanceMetrics;
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

        creditInbound(tx.getDestinationWalletId(), tx.getId(), creditSats);

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
        outbox.setDispatchedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        outbox.setLastError(null);
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    private void creditInbound(UUID walletId, UUID transactionId, long creditSats) {
        KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
        boolean watchOnly = wallet != null
                && (wallet.getKind() == KfeWalletKind.WATCH_ONLY || !wallet.isSpendable());
        if (watchOnly) {
            long chainSats = resyncChainObserved(walletId);
            long recorded = chainSats >= 0L ? chainSats : creditSats;
            movement(transactionId, walletId, "CHAIN_OBSERVED_SYNC", recorded, null, "OBSERVED");
            return;
        }
        // Dual path: payment-request monitor / custodial observer may have credited first.
        if (movementRepository.existsByTransactionIdAndMovementTypeIn(
                transactionId, KfeLedgerMovementTypes.USER_AVAILABLE_CREDIT_TYPES)) {
            log.info(
                    "[KFE Inbound Settlement] skip dual credit transactionId={} walletId={} amount={}",
                    transactionId,
                    walletId,
                    creditSats);
            recordDualSkip("inbound-settlement");
            if (wallet != null && wallet.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN) {
                resyncChainObserved(walletId);
            }
            return;
        }
        // Insert movement under unique index first; only credit when we own the row (race-safe).
        if (!tryMovement(
                transactionId,
                walletId,
                KfeLedgerMovementTypes.CREDIT_INBOUND,
                creditSats,
                null,
                "AVAILABLE")) {
            log.info(
                    "[KFE Inbound Settlement] credit race lost transactionId={} walletId={}",
                    transactionId,
                    walletId);
            recordDualSkip("inbound-settlement-race");
            if (wallet != null && wallet.getKind() == KfeWalletKind.CUSTODIAL_ONCHAIN) {
                resyncChainObserved(walletId);
            }
            return;
        }
        balanceService.creditAvailable(walletId, ASSET_BTC, creditSats);
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
                    "[KFE Inbound Settlement] chain balance sync failed walletId={}: {}",
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

    private void movement(
            UUID transactionId,
            UUID walletId,
            String movementType,
            long amountSats,
            String fromBucket,
            String toBucket) {
        tryMovement(transactionId, walletId, movementType, amountSats, fromBucket, toBucket);
    }

    private boolean tryMovement(
            UUID transactionId,
            UUID walletId,
            String movementType,
            long amountSats,
            String fromBucket,
            String toBucket) {
        if (transactionId != null
                && KfeLedgerMovementTypes.isIdempotentCreditType(movementType)
                && movementRepository.existsByTransactionIdAndMovementType(transactionId, movementType)) {
            return false;
        }
        KfeBalanceMovementEntity movement = new KfeBalanceMovementEntity();
        movement.setTransactionId(transactionId);
        movement.setWalletId(walletId);
        movement.setMovementType(movementType);
        movement.setAmountSats(amountSats);
        movement.setFromBucket(fromBucket);
        movement.setToBucket(toBucket);
        try {
            movementRepository.save(movement);
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            if (transactionId != null && KfeLedgerMovementTypes.isIdempotentCreditType(movementType)) {
                return false;
            }
            throw exception;
        }
    }

    private void recordStatement(KfeTransactionEntity tx, String providerPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, tx.getUserId()));
        payload.put("providerReferenceHash", hashService.sha256(firstNonBlank(tx.getProviderReference(), "")));
        if (providerPayload != null && !providerPayload.isBlank()) {
            payload.put("providerPayloadHash", hashService.sha256(providerPayload));
        }
        // Upsert same (user, tx) row — status/confs update in place; createdAt stays fixed.
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
        idempotencyRepository.findById(new com.kerosene.kfe.model.KfeIdempotencyId(
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
