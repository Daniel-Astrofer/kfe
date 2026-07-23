package com.kerosene.kfe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kerosene.kfe.model.KfeBalanceMovementEntity;
import com.kerosene.kfe.model.KfeExecutionOutboxEntity;
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
import java.util.Optional;
import java.util.UUID;

@Service
public class KfeExecutionTransactionHelper {

    private static final Logger log = LoggerFactory.getLogger(KfeExecutionTransactionHelper.class);
    private static final String ASSET_BTC = "BTC";

    private final KfeExecutionOutboxRepository outboxRepository;
    private final KfeTransactionRepository transactionRepository;
    private final KfeWalletRepository walletRepository;
    private final KfeIdempotencyRepository idempotencyRepository;
    private final KfeBalanceMovementRepository movementRepository;
    private final KfeBalanceService balanceService;
    private final KfeAuditLogService auditLogService;
    private final KfeStatementService statementService;
    private final KfeResponseMapper responseMapper;
    private final KfeDashboardPublisher dashboardPublisher;
    private final KfeHashService hashService;
    private final ObjectMapper objectMapper;
    private final KfeFeeSettlementService feeSettlementService;
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService;
    private final ObjectProvider<KfeLightningLiquidityService> lightningLiquidityService;
    private final ObjectProvider<KfeCustodialDepositObservationService> custodialDepositObservationService;
    private final ObjectProvider<com.kerosene.kfe.application.transaction.KfePlatformOnchainDestinationRouter>
            platformOnchainDestinationRouter;
    private final ObjectProvider<KfePlatformPeerInboundService> platformPeerInboundService;
    private final int maxRetryAttempts;

    public KfeExecutionTransactionHelper(
            KfeExecutionOutboxRepository outboxRepository,
            KfeTransactionRepository transactionRepository,
            KfeWalletRepository walletRepository,
            KfeIdempotencyRepository idempotencyRepository,
            KfeBalanceMovementRepository movementRepository,
            KfeBalanceService balanceService,
            KfeAuditLogService auditLogService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            KfeHashService hashService,
            ObjectMapper objectMapper,
            KfeFeeSettlementService feeSettlementService,
            ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService,
            ObjectProvider<KfeLightningLiquidityService> lightningLiquidityService,
            ObjectProvider<KfeCustodialDepositObservationService> custodialDepositObservationService,
            ObjectProvider<com.kerosene.kfe.application.transaction.KfePlatformOnchainDestinationRouter>
                    platformOnchainDestinationRouter,
            ObjectProvider<KfePlatformPeerInboundService> platformPeerInboundService,
            @Value("${kfe.execution.max-retry-attempts:8}") int maxRetryAttempts) {
        this.outboxRepository = outboxRepository;
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.movementRepository = movementRepository;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.hashService = hashService;
        this.objectMapper = objectMapper;
        this.feeSettlementService = feeSettlementService;
        this.onchainBalanceSyncService = onchainBalanceSyncService;
        this.lightningLiquidityService = lightningLiquidityService;
        this.custodialDepositObservationService = custodialDepositObservationService;
        this.platformOnchainDestinationRouter = platformOnchainDestinationRouter;
        this.platformPeerInboundService = platformPeerInboundService;
        if (maxRetryAttempts <= 0) {
            throw new IllegalArgumentException("maxRetryAttempts must be positive.");
        }
        this.maxRetryAttempts = maxRetryAttempts;
    }

    public record PreparationResult(
            boolean proceed,
            String operation,
            UUID transactionId,
            Long userId,
            String sourceWalletLabel,
            UUID sourceWalletId,
            String externalReference,
            long amountSats,
            long networkFeeSats,
            String memo,
            String idempotencyKey,
            String quorumProposalHash,
            Long feeRateSatsPerVbyte,
            Integer feeTargetBlocks
    ) {
        private static PreparationResult skip() {
            return new PreparationResult(
                    false, null, null, null, null, null, null, 0, 0, null, null, null, null, null);
        }
    }

    @Transactional
    public PreparationResult prepare(UUID outboxId) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId).orElse(null);
        if (outbox == null || !"PROCESSING".equals(outbox.getStatus()) || outbox.getClaimedBy() == null || outbox.getClaimedAt() == null) {
            return PreparationResult.skip();
        }

        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(outbox.getTransactionId()).orElse(null);
        if (tx == null) {
            markOutboxFailed(outbox, "TRANSACTION_NOT_FOUND", "KFE transaction does not exist.", false);
            return PreparationResult.skip();
        }
        if (tx.getStatus() == KfeTransactionStatus.SETTLED || tx.getStatus() == KfeTransactionStatus.FAILED) {
            markOutboxDispatched(outbox, firstNonBlank(tx.getProviderReference(), tx.getBlockchainTxid(), tx.getPaymentHash()));
            return PreparationResult.skip();
        }
        if (tx.getStatus() != KfeTransactionStatus.EXECUTING
                && tx.getStatus() != KfeTransactionStatus.REQUIRES_RECONCILIATION) {
            markOutboxFailed(outbox, "INVALID_TRANSACTION_STATUS",
                    "KFE transaction is not executable in status " + tx.getStatus() + ".", false);
            return PreparationResult.skip();
        }

        String op = outbox.getOperation() != null ? outbox.getOperation().trim().toUpperCase() : "";
        // Idempotency: never re-broadcast an on-chain send that already has a txid recorded.
        // Retries after statement/UI glitches used to create duplicate mempool spends.
        if ("ONCHAIN_OUTBOUND".equals(op)
                && tx.getBlockchainTxid() != null
                && !tx.getBlockchainTxid().isBlank()) {
            recordStatement(tx, tx.getSourceWalletId(), null);
            markOutboxDispatched(
                    outbox,
                    firstNonBlank(tx.getProviderReference(), tx.getBlockchainTxid(), outbox.getProviderReference()));
            dashboardPublisher.publishAfterCommit(tx.getUserId());
            log.info(
                    "[KFE Outbox] skip re-broadcast txId={} already has blockchainTxid",
                    tx.getId());
            return PreparationResult.skip();
        }
        if (!"ONCHAIN_OUTBOUND".equals(op) && !"LIGHTNING_OUTBOUND".equals(op)) {
            if ("ONCHAIN_INBOUND".equals(op) || "LIGHTNING_INBOUND".equals(op)) {
                markRequiresReconciliation(
                        outbox.getId(),
                        tx.getId(),
                        "INBOUND_REQUIRES_TRUSTED_MONITOR",
                        "Inbound settlement must be performed by a trusted KFE network monitor.");
            } else {
                markFinalFailure(
                        outbox.getId(),
                        tx.getId(),
                        "UNSUPPORTED_OPERATION",
                        "Unsupported KFE outbox operation " + outbox.getOperation() + ".");
            }
            return PreparationResult.skip();
        }

        KfeWalletEntity sourceWallet = walletRepository.findById(tx.getSourceWalletId())
                .orElseThrow(() -> new IllegalStateException("Source KFE wallet not found."));

        JsonNode payload = payload(outbox);
        String externalReference = text(payload, "externalReference", tx.getExternalReference());
        String memo = text(payload, "memo", tx.getMemo());
        Long feeRate = longOrNull(payload, "feeRateSatsPerVbyte", "feeRateSatPerVbyte");
        Integer feeTarget = intOrNull(payload, "feeTargetBlocks", "confirmationTarget");
        // Derive sat/vB from quoted network fee when client only sent max-fee sats.
        if ((feeRate == null || feeRate <= 0L) && tx.getNetworkFeeSats() > 0L) {
            long vbytes = longOrNull(payload, "estimatedVbytes") != null
                    ? longOrNull(payload, "estimatedVbytes")
                    : 180L;
            if (vbytes > 0L) {
                feeRate = Math.max(1L, (tx.getNetworkFeeSats() + vbytes - 1L) / vbytes);
            }
        }

        return new PreparationResult(
                true,
                op,
                tx.getId(),
                tx.getUserId(),
                sourceWallet.getLabel(),
                sourceWallet.getId(),
                externalReference,
                tx.getReceiverAmountSats(),
                tx.getNetworkFeeSats(),
                memo,
                tx.getIdempotencyKey(),
                tx.getQuorumProposalHash(),
                feeRate,
                feeTarget
        );
    }

    /**
     * Records a successful on-chain broadcast without unlocking the user reserve yet.
     * Funds stay LOCKED until {@link #settleOutboundWhenConfirmed} after N confirmations.
     */
    @Transactional
    public void recordOutboundBroadcast(
            UUID outboxId,
            UUID transactionId,
            String provider,
            String providerReference,
            String blockchainTxid,
            long feeSats,
            UUID sourceWalletId,
            String providerPayload) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, providerReference)) {
            return;
        }
        if (blockchainTxid == null || blockchainTxid.isBlank()) {
            throw new IllegalArgumentException("blockchainTxid is required after broadcast.");
        }

        String normalizedTxid = blockchainTxid.trim();
        // Already recorded (retry after a partial success) — close outbox, do not re-touch fee.
        if (tx.getBlockchainTxid() != null
                && !tx.getBlockchainTxid().isBlank()
                && tx.getBlockchainTxid().equalsIgnoreCase(normalizedTxid)) {
            recordStatement(tx, sourceWalletId, providerPayload);
            markOutboxDispatched(outbox, firstNonBlank(providerReference, normalizedTxid));
            dashboardPublisher.publishAfterCommit(tx.getUserId());
            return;
        }

        tx.setProvider(trim(provider, 64));
        tx.setProviderReference(firstNonBlank(providerReference, normalizedTxid));
        tx.setBlockchainTxid(normalizedTxid);
        tx.setConfirmations(0);
        if (!reconcileOutboundFee(outbox, tx, sourceWalletId, feeSats)) {
            return;
        }
        // Keep EXECUTING — reserve remains locked until chain confirmation monitor settles.
        // Persist txid BEFORE statement so a statement glitch cannot leave EXECUTING with no txid.
        transactionRepository.saveAndFlush(tx);
        recordStatement(tx, sourceWalletId, providerPayload);
        updateIdempotency(tx);
        markOutboxDispatched(outbox, firstNonBlank(providerReference, normalizedTxid));
        audit(tx, "KFE_PSBT_WORKFLOW_BROADCAST", tx.getStatus(), tx.getStatus(),
                Map.of(
                        "txidHash", hashService.sha256(normalizedTxid),
                        "providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, normalizedTxid))));
        dashboardPublisher.publishAfterCommit(tx.getUserId());
        // Peer expose + deposit observe MUST run after commit. Audit takes
        // pg_advisory_xact_lock(GLOBAL_AUDIT_APPENDER) for the whole outer TX; doing RPC/observe
        // here held that lock for minutes, exhausted Hikari, and made /health/ready fail so the
        // API (including payment-requests) stopped answering.
        UUID outboundId = tx.getId();
        String destinationAddress = tx.getExternalReference();
        // Never block the submit HTTP thread: peer expose + deposit observe can take seconds
        // (and used to hold the client on "Autorizando…" for 10–60s after broadcast).
        runAfterCommitAsync(() -> {
            transactionRepository.findById(outboundId).ifPresent(this::exposePlatformPeerInbound);
            kickPlatformDepositObservation(destinationAddress);
        });
    }

    /**
     * Runs {@code action} only after the outer TX has fully completed and resources (EntityManager /
     * connection) are unbound. Using {@code afterCommit} alone is unsafe: Spring still holds the
     * session until {@code afterCompletion}, so nested {@code @Transactional} joins a dead context
     * ("no transaction is in progress") and peer inbound never lands.
     *
     * <p>Work is always dispatched off the calling thread so API handlers (sync-on-submit) return
     * as soon as the ledger row + txid are committed.
     */
    private void runAfterCommit(Runnable action) {
        runAfterCommitAsync(action);
    }

    private void runAfterCommitAsync(Runnable action) {
        if (action == null) {
            return;
        }
        Runnable safe = () -> {
            try {
                action.run();
            } catch (RuntimeException exception) {
                log.warn("[KFE Execution] after-completion hook failed: {}", exception.getMessage());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            Thread.startVirtualThread(safe);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    return;
                }
                Thread.startVirtualThread(safe);
            }
        });
    }

    private void exposePlatformPeerInbound(KfeTransactionEntity outbound) {
        KfePlatformPeerInboundService peer = platformPeerInboundService.getIfAvailable();
        if (peer == null) {
            return;
        }
        try {
            peer.exposeAfterOutboundBroadcast(outbound);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Execution] platform peer inbound expose failed txId={}: {}",
                    outbound.getId(),
                    exception.getMessage());
        }
    }

    private void kickPlatformDepositObservation(String destinationAddress) {
        if (destinationAddress == null || destinationAddress.isBlank()) {
            return;
        }
        var router = platformOnchainDestinationRouter.getIfAvailable();
        var observer = custodialDepositObservationService.getIfAvailable();
        if (router == null || observer == null) {
            return;
        }
        try {
            Optional<UUID> sink = router.findPlatformSinkWalletIdForAddress(destinationAddress.trim());
            if (sink.isEmpty()) {
                sink = router.resolveRecipientOnchainSinkWalletId(destinationAddress.trim());
            }
            sink.ifPresent(walletId -> {
                try {
                    observer.observeWallet(walletId);
                } catch (RuntimeException exception) {
                    log.debug(
                            "[KFE Execution] post-broadcast deposit observe failed walletId={}: {}",
                            walletId,
                            exception.getMessage());
                }
            });
        } catch (RuntimeException exception) {
            log.debug(
                    "[KFE Execution] platform deposit kick failed: {}",
                    exception.getMessage());
        }
    }

    /**
     * Persist chain confirmation progress for UI rings (0/6…6/6).
     *
     * <p>{@link Propagation#REQUIRES_NEW}: committed independently of settle/audit so a hung
     * settle cannot leave the app frozen at 0 confirmations while Core already has 1+.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchOutboundConfirmations(UUID transactionId, int confirmations) {
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (tx == null || confirmations <= tx.getConfirmations()) {
            return;
        }
        tx.setConfirmations(confirmations);
        transactionRepository.saveAndFlush(tx);
        // Refresh 24h statement so the app shows confirmation progress (not infinite PENDING).
        recordStatement(tx, firstNonNull(tx.getSourceWalletId(), tx.getDestinationWalletId()), null);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    /**
     * Unlocks/settles reserved debit after the outbound tx is monitored with enough confirmations.
     *
     * <p>Callers must already have persisted conf progress via {@link #touchOutboundConfirmations}
     * so the UI keeps advancing if this method fails mid-way (audit lock, fee race, etc.).
     */
    @Transactional
    public boolean settleOutboundWhenConfirmed(UUID transactionId, int confirmations) {
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (tx == null) {
            return false;
        }
        if (tx.getStatus() == KfeTransactionStatus.SETTLED) {
            // Still advance confs on already-settled rows (rings past minConfirmations).
            if (confirmations > tx.getConfirmations()) {
                tx.setConfirmations(confirmations);
                transactionRepository.saveAndFlush(tx);
                recordStatement(tx, firstNonNull(tx.getSourceWalletId(), tx.getDestinationWalletId()), null);
                dashboardPublisher.publishAfterCommit(tx.getUserId());
            }
            return true;
        }
        if (tx.getStatus() != KfeTransactionStatus.EXECUTING
                && tx.getStatus() != KfeTransactionStatus.VALIDATING
                && tx.getStatus() != KfeTransactionStatus.REQUIRES_RECONCILIATION) {
            return false;
        }
        if (tx.getBlockchainTxid() == null || tx.getBlockchainTxid().isBlank()) {
            return false;
        }
        if (tx.getSourceWalletId() == null) {
            return false;
        }

        KfeExecutionOutboxEntity outbox = outboxRepository.findByTransactionId(transactionId).stream()
                .findFirst()
                .orElse(null);

        // Flush confs before heavy settle work so a later failure still leaves progress visible.
        tx.setConfirmations(Math.max(tx.getConfirmations(), confirmations));
        transactionRepository.saveAndFlush(tx);

        String providerReference = firstNonBlank(tx.getProviderReference(), tx.getBlockchainTxid());
        String provider = firstNonBlank(tx.getProvider(), "BITCOIN_CORE_QUORUM");

        balanceService.settleReservedDebit(tx.getSourceWalletId(), ASSET_BTC, tx.getTotalDebitSats());
        movement(tx.getId(), tx.getSourceWalletId(), "SETTLE_DEBIT", tx.getTotalDebitSats(), "LOCKED", null);
        transition(tx, KfeTransactionStatus.SETTLED, "KFE_TRANSACTION_SETTLED",
                Map.of(
                        "providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, "")),
                        "confirmations", String.valueOf(confirmations),
                        "provider", provider));
        try {
            feeSettlementService.creditKeroseneFee(tx);
        } catch (RuntimeException feeFailure) {
            // Fee credit is idempotent on retry; do not roll back unlock/SETTLED for fee audit glitches.
            log.warn(
                    "[KFE Execution] kerosene fee settle deferred txId={}: {}",
                    tx.getId(),
                    feeFailure.getMessage());
        }
        recordStatement(tx, tx.getSourceWalletId(), null);
        updateIdempotency(tx);
        if (outbox != null) {
            KfeExecutionOutboxEntity locked = outboxRepository.findByIdForUpdate(outbox.getId()).orElse(outbox);
            locked.setProviderReference(providerReference);
            markOutboxDispatched(locked, providerReference);
        }
        UUID sourceWalletId = tx.getSourceWalletId();
        dashboardPublisher.publishAfterCommit(tx.getUserId());
        // Never block the confirmation monitor on chain RPC. Scheduled onchain-balance-sync
        // will refresh observed_sats; kick async after commit so settle returns immediately.
        runAfterCommit(() -> Thread.startVirtualThread(
                () -> resyncCustodialObserved(sourceWalletId)));
        return true;
    }

    @Transactional
    public void settleOutbound(
            UUID outboxId,
            UUID transactionId,
            String provider,
            String providerReference,
            String blockchainTxid,
            long feeSats,
            UUID sourceWalletId,
            String providerPayload) {
        // Immediate settle path (e.g. lightning or tests). On-chain production uses
        // recordOutboundBroadcast + settleOutboundWhenConfirmed.
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, providerReference)) {
            return;
        }

        tx.setProvider(provider);
        tx.setProviderReference(providerReference);
        tx.setBlockchainTxid(blockchainTxid);
        if (!reconcileOutboundFee(outbox, tx, sourceWalletId, feeSats)) {
            return;
        }

        balanceService.settleReservedDebit(sourceWalletId, ASSET_BTC, tx.getTotalDebitSats());
        movement(tx.getId(), sourceWalletId, "SETTLE_DEBIT", tx.getTotalDebitSats(), "LOCKED", null);
        transition(tx, KfeTransactionStatus.SETTLED, "KFE_TRANSACTION_SETTLED",
                Map.of("providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, ""))));
        feeSettlementService.creditKeroseneFee(tx);
        recordStatement(tx, sourceWalletId, providerPayload);
        updateIdempotency(tx);
        markOutboxDispatched(outbox, providerReference);
        resyncCustodialObserved(sourceWalletId);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    @Transactional
    public void settleOutboundLightning(
            UUID outboxId,
            UUID transactionId,
            String provider,
            String providerReference,
            String blockchainTxid,
            String paymentHash,
            long feeSats,
            UUID sourceWalletId,
            String providerPayload) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, firstNonBlank(providerReference, paymentHash, blockchainTxid))) {
            return;
        }

        tx.setProvider(provider);
        tx.setProviderReference(providerReference);
        tx.setBlockchainTxid(blockchainTxid);
        tx.setPaymentHash(paymentHash);
        if (!reconcileOutboundFee(outbox, tx, sourceWalletId, feeSats)) {
            return;
        }

        balanceService.settleReservedDebit(sourceWalletId, ASSET_BTC, tx.getTotalDebitSats());
        movement(tx.getId(), sourceWalletId, "SETTLE_DEBIT", tx.getTotalDebitSats(), "LOCKED", null);
        consumeLightningLiquidity(tx.getId());
        transition(tx, KfeTransactionStatus.SETTLED, "KFE_TRANSACTION_SETTLED",
                Map.of("providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, ""))));
        feeSettlementService.creditKeroseneFee(tx);
        recordStatement(tx, sourceWalletId, providerPayload);
        updateIdempotency(tx);
        markOutboxDispatched(outbox, providerReference);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    private void consumeLightningLiquidity(UUID transactionId) {
        KfeLightningLiquidityService liquidity = lightningLiquidityService.getIfAvailable();
        if (liquidity != null) {
            liquidity.consumeForTransaction(transactionId);
        }
    }

    private void releaseLightningLiquidity(UUID transactionId) {
        KfeLightningLiquidityService liquidity = lightningLiquidityService.getIfAvailable();
        if (liquidity != null) {
            liquidity.releaseForTransaction(transactionId);
        }
    }

    private boolean reconcileOutboundFee(
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            UUID sourceWalletId,
            long actualFeeSats) {
        long reservedFeeSats = tx.getNetworkFeeSats();
        if (actualFeeSats < 0L) {
            markRequiresReconciliation(
                    outbox,
                    tx,
                    "INVALID_ACTUAL_FEE",
                    "Provider returned a negative network fee.");
            return false;
        }
        if (actualFeeSats > reservedFeeSats) {
            markRequiresReconciliation(
                    outbox,
                    tx,
                    "ACTUAL_FEE_EXCEEDS_RESERVED",
                    "Actual network fee exceeds the reserved fee limit.");
            return false;
        }

        final long debitWithoutNetworkFee;
        final long reconciledTotalDebit;
        try {
            debitWithoutNetworkFee = Math.subtractExact(tx.getTotalDebitSats(), reservedFeeSats);
            reconciledTotalDebit = Math.addExact(debitWithoutNetworkFee, actualFeeSats);
        } catch (ArithmeticException exception) {
            markRequiresReconciliation(
                    outbox,
                    tx,
                    "FEE_RECONCILIATION_OVERFLOW",
                    "Network fee reconciliation overflowed the transaction amount.");
            return false;
        }
        if (debitWithoutNetworkFee <= 0L
                || reconciledTotalDebit <= 0L
                || reconciledTotalDebit > tx.getTotalDebitSats()) {
            markRequiresReconciliation(
                    outbox,
                    tx,
                    "INVALID_RECONCILED_DEBIT",
                    "Network fee reconciliation produced an invalid total debit.");
            return false;
        }

        long releaseSats = tx.getTotalDebitSats() - reconciledTotalDebit;
        tx.setNetworkFeeSats(actualFeeSats);
        tx.setTotalDebitSats(reconciledTotalDebit);
        if (releaseSats > 0L) {
            balanceService.releaseReserved(sourceWalletId, ASSET_BTC, releaseSats);
            movement(tx.getId(), sourceWalletId, "RELEASE_FEE_RESERVE", releaseSats, "LOCKED", "AVAILABLE");
        }
        return true;
    }

    @Transactional
    public void markUnknown(
            UUID outboxId,
            UUID transactionId,
            String providerReference,
            String providerPayload,
            String message) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, providerReference)) {
            return;
        }

        tx.setProviderReference(firstNonBlank(providerReference, tx.getProviderReference()));
        tx.setFailureCode("PROVIDER_RESULT_UNKNOWN");
        tx.setFailureMessage(trim(message, 255));
        transition(tx, KfeTransactionStatus.REQUIRES_RECONCILIATION, "KFE_TRANSACTION_REQUIRES_RECONCILIATION",
                Map.of("providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, ""))));
        recordStatement(tx, tx.getSourceWalletId(), providerPayload);
        updateIdempotency(tx);

        outbox.setAttempts(outbox.getAttempts() + 1);
        outbox.setStatus("UNKNOWN");
        outbox.setProviderReference(providerReference);
        outbox.setLastError(trim(message, 1000));
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    @Transactional
    public void markRetryableFailure(
            UUID outboxId,
            UUID transactionId,
            String code,
            String message) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, null)) {
            return;
        }

        if (outbox.getAttempts() + 1 >= maxRetryAttempts) {
            finalizeFailure(
                    outbox,
                    tx,
                    "PROVIDER_RETRY_EXHAUSTED",
                    "Provider execution failed after " + maxRetryAttempts + " attempts: " + message);
            return;
        }

        outbox.setAttempts(outbox.getAttempts() + 1);
        outbox.setStatus("FAILED_RETRYABLE");
        outbox.setLastError(trim(code + ": " + message, 1000));
        outbox.setNextAttemptAt(LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(Math.min(60, 1L << Math.min(outbox.getAttempts(), 5))));
        clearClaim(outbox);
        outboxRepository.save(outbox);
        audit(tx, "KFE_EXECUTION_RETRYABLE_FAILURE", tx.getStatus(), tx.getStatus(),
                Map.of("failureCode", code, "errorHash", hashService.sha256(message)));
    }

    @Transactional
    public void markFinalFailure(
            UUID outboxId,
            UUID transactionId,
            String code,
            String message) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, null)) {
            return;
        }

        finalizeFailure(outbox, tx, code, message);
    }

    private void finalizeFailure(
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            String code,
            String message) {
        if (tx.getSourceWalletId() != null && tx.getTotalDebitSats() > 0L) {
            balanceService.releaseReserved(tx.getSourceWalletId(), ASSET_BTC, tx.getTotalDebitSats());
            movement(tx.getId(), tx.getSourceWalletId(), "RELEASE_RESERVE", tx.getTotalDebitSats(), "LOCKED", "AVAILABLE");
        }
        releaseLightningLiquidity(tx.getId());
        tx.setFailureCode(trim(code, 64));
        tx.setFailureMessage(trim(message, 255));
        transition(tx, KfeTransactionStatus.FAILED, "KFE_TRANSACTION_FAILED",
                Map.of("failureCode", code, "errorHash", hashService.sha256(message)));
        recordStatement(tx, firstNonNull(tx.getSourceWalletId(), tx.getDestinationWalletId()), null);
        updateIdempotency(tx);
        markOutboxFailed(outbox, code, message, false);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    @Transactional
    public void markRequiresReconciliation(
            UUID outboxId,
            UUID transactionId,
            String code,
            String message) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));

        markRequiresReconciliation(outbox, tx, code, message);
    }

    private void markRequiresReconciliation(
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            String code,
            String message) {
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, null)) {
            return;
        }

        tx.setFailureCode(trim(code, 64));
        tx.setFailureMessage(trim(message, 255));
        transition(tx, KfeTransactionStatus.REQUIRES_RECONCILIATION, "KFE_TRANSACTION_REQUIRES_RECONCILIATION",
                Map.of("reason", code));
        recordStatement(tx, firstNonNull(tx.getDestinationWalletId(), tx.getSourceWalletId()), null);
        updateIdempotency(tx);

        outbox.setAttempts(outbox.getAttempts() + 1);
        outbox.setStatus("UNKNOWN");
        outbox.setProviderReference(firstNonBlank(
                outbox.getProviderReference(),
                tx.getProviderReference(),
                tx.getBlockchainTxid(),
                tx.getPaymentHash()));
        outbox.setLastError(trim(code + ": " + message, 1000));
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    private void markOutboxDispatched(KfeExecutionOutboxEntity outbox, String providerReference) {
        outbox.setStatus("DISPATCHED");
        outbox.setProviderReference(providerReference);
        outbox.setDispatchedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        outbox.setLastError(null);
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    /**
     * After custodial outbound settles, re-probe chain observed so dual-ledger drift is short-lived.
     * Failures are non-fatal — scheduled on-chain sync remains the backstop.
     */
    private void resyncCustodialObserved(UUID walletId) {
        if (walletId == null) {
            return;
        }
        KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
        if (wallet == null || wallet.getKind() != KfeWalletKind.CUSTODIAL_ONCHAIN) {
            return;
        }
        KfeOnchainBalanceSyncService sync = onchainBalanceSyncService.getIfAvailable();
        if (sync == null) {
            return;
        }
        try {
            long probed = sync.syncWallet(walletId);
            log.info(
                    "[KFE Execution] custodial observed resync walletId={} resultSats={}",
                    walletId,
                    probed);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Execution] custodial observed resync failed walletId={}: {}",
                    walletId,
                    exception.getMessage());
        }
    }

    private boolean completeTerminalOutboxIfTransactionTerminal(
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            String providerReference) {
        if (tx.getStatus() == KfeTransactionStatus.SETTLED) {
            markOutboxDispatched(outbox, firstNonBlank(
                    providerReference,
                    tx.getProviderReference(),
                    tx.getBlockchainTxid(),
                    tx.getPaymentHash()));
            return true;
        }
        if (tx.getStatus() == KfeTransactionStatus.FAILED) {
            markOutboxFinalFailed(outbox, tx);
            return true;
        }
        return false;
    }

    private void markOutboxFailed(
            KfeExecutionOutboxEntity outbox,
            String code,
            String message,
            boolean retryable) {
        outbox.setAttempts(outbox.getAttempts() + 1);
        outbox.setStatus(retryable ? "FAILED_RETRYABLE" : "FAILED_FINAL");
        String finalMsg = message != null && !message.isBlank() ? message : "KFE provider execution failed.";
        outbox.setLastError(trim(code + ": " + finalMsg, 1000));
        outbox.setNextAttemptAt(retryable
                ? LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(Math.min(60, 1L << Math.min(outbox.getAttempts(), 5)))
                : null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    private void markOutboxFinalFailed(KfeExecutionOutboxEntity outbox, KfeTransactionEntity tx) {
        outbox.setStatus("FAILED_FINAL");
        String code = firstNonBlank(tx.getFailureCode(), "TRANSACTION_FAILED");
        String message = firstNonBlank(tx.getFailureMessage(), "KFE transaction is already failed.");
        outbox.setLastError(trim(code + ": " + message, 1000));
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    private void transition(
            KfeTransactionEntity tx,
            KfeTransactionStatus target,
            String eventType,
            Map<String, ?> auditPayload) {
        KfeTransactionStatus previous = tx.getStatus();
        tx.setStatus(target);
        transactionRepository.save(tx);
        audit(tx, eventType, previous, target, auditPayload);
    }

    private void audit(
            KfeTransactionEntity tx,
            String eventType,
            KfeTransactionStatus from,
            KfeTransactionStatus to,
            Map<String, ?> payload) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        redacted.put("transactionId", tx.getId().toString());
        redacted.put("idempotencyHash", hashService.sha256(tx.getIdempotencyKey()));
        if (payload != null) {
            redacted.putAll(payload);
        }
        auditLogService.record(eventType, tx.getId(), tx.getSourceWalletId(), from, to, redacted);
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

    private void recordStatement(KfeTransactionEntity tx, UUID walletId, String providerPayload) {
        if (tx == null || tx.getUserId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, tx.getUserId()));
        if (providerPayload != null && !providerPayload.isBlank()) {
            payload.put("providerPayloadHash", hashService.sha256(providerPayload));
        }
        // Best-effort: outbox/monitor must not die if statement cache glitches.
        statementService.recordUserStatementBestEffort(tx.getUserId(), walletId, tx, payload);
    }

    private void updateIdempotency(KfeTransactionEntity tx) {
        idempotencyRepository.findById(new com.kerosene.kfe.model.KfeIdempotencyId(tx.getUserId(), tx.getIdempotencyKey())).ifPresent(entity -> {
            entity.setStatus(tx.getStatus().name());
            idempotencyRepository.save(entity);
        });
    }

    private JsonNode payload(KfeExecutionOutboxEntity outbox) {
        if (outbox.getPayloadJson() == null || outbox.getPayloadJson().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(outbox.getPayloadJson());
        } catch (Exception exception) {
            throw new IllegalArgumentException("KFE outbox payload is not valid JSON.", exception);
        }
    }

    private void clearClaim(KfeExecutionOutboxEntity outbox) {
        outbox.setClaimedBy(null);
        outbox.setClaimedAt(null);
    }

    private String text(JsonNode payload, String field, String fallback) {
        JsonNode value = payload.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private Long longOrNull(JsonNode payload, String... fields) {
        if (payload == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = payload.path(field);
            if (value.isIntegralNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                try {
                    return Long.parseLong(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // try next field
                }
            }
        }
        return null;
    }

    private Integer intOrNull(JsonNode payload, String... fields) {
        Long value = longOrNull(payload, fields);
        if (value == null) {
            return null;
        }
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            return null;
        }
        return value.intValue();
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

    private UUID firstNonNull(UUID first, UUID second) {
        return first != null ? first : second;
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
