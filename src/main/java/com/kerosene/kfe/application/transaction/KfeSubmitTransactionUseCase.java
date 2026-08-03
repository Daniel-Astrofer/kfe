package com.kerosene.kfe.application.transaction;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.kerosene.common.financial.FinancialTickerPort;
import com.kerosene.common.financial.FinancialNotificationPort;
import com.kerosene.kfe.application.settlement.BinarySettlementGate;
import com.kerosene.kfe.application.settlement.SettlementGateCommand;
import com.kerosene.kfe.application.settlement.SettlementGateResult;
import com.kerosene.kfe.dto.KfeSubmitTransactionRequest;
import com.kerosene.kfe.dto.KfeTransactionResponse;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeIdempotencyEntity;
import com.kerosene.kfe.model.KfePaymentRequestEntity;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.service.KfeBalanceService;
import com.kerosene.kfe.service.KfeDashboardPublisher;
import com.kerosene.kfe.service.KfeExecutionOutboxProcessor;
import com.kerosene.kfe.service.KfeExecutionOutboxService;
import com.kerosene.kfe.service.KfeFeeSettlementService;
import com.kerosene.kfe.service.KfeHashService;
import com.kerosene.kfe.service.KfeLightningLiquidityService;
import com.kerosene.kfe.service.KfeNetworkFeeEstimateService;
import com.kerosene.kfe.service.KfePricingService;
import com.kerosene.kfe.service.KfeResponseMapper;
import com.kerosene.kfe.service.KfeVaultMeshIntentService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

@Service
public class KfeSubmitTransactionUseCase {

    private static final Logger log = LoggerFactory.getLogger(KfeSubmitTransactionUseCase.class);
    private static final String ASSET_BTC = "BTC";
    private static final BigDecimal SATS_PER_BTC = new BigDecimal("100000000");
    private static final String SYNC_WORKER_ID = "kfe-submit-sync-lightning";

    private final KfeTransactionRepository transactionRepository;
    private final KfePricingService pricingService;
    private final KfeNetworkFeeEstimateService networkFeeEstimateService;
    private final FinancialTickerPort tickerPort;
    private final KfeBalanceService balanceService;
    private final BinarySettlementGate binarySettlementGate;
    private final KfeHashService hashService;
    private final KfeResponseMapper responseMapper;
    private final KfeDashboardPublisher dashboardPublisher;
    private final KfeTransactionRequestValidator validator;
    private final KfeTransactionAuthorizationUseCase authorizationUseCase;
    private final KfeTransactionIdempotencyUseCase idempotencyUseCase;
    private final KfeTransactionWalletResolver walletResolver;
    private final KfePlatformOnchainDestinationRouter onchainDestinationRouter;
    private final KfeTransactionStateMachine stateMachine;
    private final KfeBalanceMovementRecorder movementRecorder;
    private final KfeTransactionOutboxUseCase outboxUseCase;
    private final KfeTransactionStatementRecorder statementRecorder;
    private final KfeFeeSettlementService feeSettlementService;
    private final KfeInternalPaymentRequestSettlementUseCase paymentRequestSettlementUseCase;
    private final KfeLightningLiquidityService lightningLiquidityService;
    private final FinancialNotificationPort notificationPort;
    private final KfeExecutionOutboxService outboxService;
    private final KfeExecutionOutboxProcessor outboxProcessor;
    private final KfeVaultMeshIntentService vaultMeshIntentService;
    private final boolean lightningSyncOnSubmit;
    private final boolean onchainSyncOnSubmit;
    private final TransactionTemplate transactionTemplate;

    public KfeSubmitTransactionUseCase(
            KfeTransactionRepository transactionRepository,
            KfePricingService pricingService,
            KfeNetworkFeeEstimateService networkFeeEstimateService,
            FinancialTickerPort tickerPort,
            KfeBalanceService balanceService,
            BinarySettlementGate binarySettlementGate,
            KfeHashService hashService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            KfeTransactionRequestValidator validator,
            KfeTransactionAuthorizationUseCase authorizationUseCase,
            KfeTransactionIdempotencyUseCase idempotencyUseCase,
            KfeTransactionWalletResolver walletResolver,
            KfePlatformOnchainDestinationRouter onchainDestinationRouter,
            KfeTransactionStateMachine stateMachine,
            KfeBalanceMovementRecorder movementRecorder,
            KfeTransactionOutboxUseCase outboxUseCase,
            KfeTransactionStatementRecorder statementRecorder,
            KfeFeeSettlementService feeSettlementService,
            KfeInternalPaymentRequestSettlementUseCase paymentRequestSettlementUseCase,
            KfeLightningLiquidityService lightningLiquidityService,
            FinancialNotificationPort notificationPort,
            KfeExecutionOutboxService outboxService,
            KfeExecutionOutboxProcessor outboxProcessor,
            KfeVaultMeshIntentService vaultMeshIntentService,
            @Value("${kfe.execution.lightning.sync-on-submit:true}") boolean lightningSyncOnSubmit,
            @Value("${kfe.execution.onchain.sync-on-submit:true}") boolean onchainSyncOnSubmit,
            PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.pricingService = pricingService;
        this.networkFeeEstimateService = networkFeeEstimateService;
        this.tickerPort = tickerPort;
        this.balanceService = balanceService;
        this.binarySettlementGate = binarySettlementGate;
        this.hashService = hashService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.validator = validator;
        this.authorizationUseCase = authorizationUseCase;
        this.idempotencyUseCase = idempotencyUseCase;
        this.walletResolver = walletResolver;
        this.onchainDestinationRouter = onchainDestinationRouter;
        this.stateMachine = stateMachine;
        this.movementRecorder = movementRecorder;
        this.outboxUseCase = outboxUseCase;
        this.statementRecorder = statementRecorder;
        this.feeSettlementService = feeSettlementService;
        this.paymentRequestSettlementUseCase = paymentRequestSettlementUseCase;
        this.lightningLiquidityService = lightningLiquidityService;
        this.notificationPort = notificationPort;
        this.outboxService = outboxService;
        this.outboxProcessor = outboxProcessor;
        this.vaultMeshIntentService = vaultMeshIntentService;
        this.lightningSyncOnSubmit = lightningSyncOnSubmit;
        this.onchainSyncOnSubmit = onchainSyncOnSubmit;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Submits a KFE-only transaction.
     *
     * <p>Transactional authorization (remote HTTP to auth server for Device Key / passkey step-up)
     * runs <strong>outside</strong> the DB transaction so Hikari connections are not held during
     * network I/O — holding them was a root cause of multi-minute hangs and pool exhaustion under load.
     *
     * <p>Idempotency reservation and ledger mutation stay in one DB transaction: if reservation fails,
     * no transaction row, balance movement, outbox item, statement, or dashboard side effect should be emitted.
     *
     * <p>Lightning outbound: after commit, the outbox item is claimed and processed <strong>in the
     * request path</strong> (sync-on-submit) so the API returns SETTLED/FAILED instead of leaving
     * the client spinning on EXECUTING. Async worker remains the safety net if sync is disabled
     * or claim races. On-chain stays fully async.
     */
    public KfeTransactionResponse submit(Long userId, KfeSubmitTransactionRequest request) {
        return submit(userId, request, null);
    }

    public KfeTransactionResponse submit(Long userId, KfeSubmitTransactionRequest request, String deviceHash) {
        request = walletResolver.resolveInternalDestinationReference(request);
        // On-chain → known Kerosene address: deliver to recipient's custodial/cold sink.
        request = onchainDestinationRouter.resolve(request);
        validator.validate(request);

        String requestHash = idempotencyUseCase.requestHash(userId, request);
        KfeIdempotencyEntity existingIdempotency = idempotencyUseCase.find(userId, request.idempotencyKey());
        if (existingIdempotency != null) {
            return idempotencyUseCase.existingResponse(existingIdempotency, requestHash);
        }

        walletResolver.requireNotSelfPayment(userId, request);
        // Remote step-up / TOTP verification — no open EntityManager / pool connection here.
        authorizationUseCase.authorize(userId, request, deviceHash);

        final KfeSubmitTransactionRequest authorizedRequest = request;
        SubmissionOutcome outcome = transactionTemplate.execute(
                status -> submitAuthorized(userId, authorizedRequest, requestHash));
        if (outcome == null || outcome.response() == null) {
            throw new IllegalStateException("KFE transaction submission returned no response.");
        }

        if (outcome.lightningOutboxId() != null && lightningSyncOnSubmit) {
            return drainOutboxSync(outcome, "Lightning");
        }
        // Broadcast on-chain in the request path so platform peer inbound can be exposed
        // immediately (recipient history + push) instead of waiting for the async worker.
        if (outcome.onchainOutboxId() != null && onchainSyncOnSubmit) {
            return drainOutboxSync(outcome, "Onchain");
        }
        return outcome.response();
    }

    private SubmissionOutcome submitAuthorized(
            Long userId,
            KfeSubmitTransactionRequest request,
            String requestHash) {
        KfeIdempotencyEntity idempotency;
        try {
            idempotency = idempotencyUseCase.reserve(userId, request, requestHash);
        } catch (DataIntegrityViolationException | ConstraintViolationException ex) {
            KfeTransactionResponse existing =
                    idempotencyUseCase.getExistingByIdempotency(userId, request.idempotencyKey(), requestHash);
            return new SubmissionOutcome(existing, null, null, null);
        }

        KfePaymentRequestEntity paymentRequest = paymentRequestSettlementUseCase.lockAndValidate(request);

        KfeTransactionEntity tx = createIntent(userId, request);
        stateMachine.audit(tx, "KFE_TRANSACTION_INTENT", null, tx.getStatus(), null);

        PreparedTransaction prepared = validateQuoteAndQuorum(userId, tx, request, requestHash);
        WalletLock lock = reserveAndLock(request, prepared);
        UUID outboxId = routeLockedTransaction(userId, request, prepared, lock);
        paymentRequestSettlementUseCase.markPaid(paymentRequest, prepared.tx());

        KfeTransactionResponse response =
                completePublishAndRespond(userId, idempotency, prepared.tx(), lock.destinationWallet());
        UUID lightningOutboxId =
                request.rail() == KfeRail.LIGHTNING ? outboxId : null;
        UUID onchainOutboxId =
                request.rail() == KfeRail.ONCHAIN ? outboxId : null;
        return new SubmissionOutcome(response, prepared.tx().getId(), lightningOutboxId, onchainOutboxId);
    }

    /**
     * After the ledger TX commits: claim the outbox item and run provider execution now
     * (Lightning pay or on-chain broadcast). Reloads the transaction for the HTTP response.
     */
    private KfeTransactionResponse drainOutboxSync(SubmissionOutcome outcome, String railLabel) {
        UUID outboxId = outcome.lightningOutboxId() != null
                ? outcome.lightningOutboxId()
                : outcome.onchainOutboxId();
        UUID transactionId = outcome.transactionId();
        try {
            var claim = outboxService.claimImmediate(outboxId, SYNC_WORKER_ID);
            if (claim.isEmpty()) {
                log.info(
                        "[KFE Submit] {} outbox already claimed (async worker) outboxId={} txId={}",
                        railLabel,
                        outboxId,
                        transactionId);
            } else {
                log.info(
                        "[KFE Submit] {} sync-on-submit drain starting outboxId={} txId={}",
                        railLabel,
                        outboxId,
                        transactionId);
                outboxProcessor.process(claim.orElseThrow());
            }
        } catch (RuntimeException exception) {
            // Processor already marks retryable/final failure in most paths; never fail the HTTP
            // envelope if the intent was recorded — client can poll history.
            log.warn(
                    "[KFE Submit] {} sync drain error outboxId={} txId={}: {}",
                    railLabel,
                    outboxId,
                    transactionId,
                    exception.getMessage());
        }

        if (transactionId == null) {
            return outcome.response();
        }
        return transactionRepository.findById(transactionId)
                .map(responseMapper::toTransactionResponse)
                .orElse(outcome.response());
    }

    private PreparedTransaction validateQuoteAndQuorum(
            Long userId,
            KfeTransactionEntity tx,
            KfeSubmitTransactionRequest request,
            String requestHash) {
        stateMachine.transition(tx, KfeTransactionStatus.VALIDATING, "KFE_TRANSACTION_VALIDATING",
                Map.of("requestHash", requestHash));
        KfeWalletEntity sourceWallet = walletResolver.resolveSourceWallet(userId, request);
        KfeWalletEntity destinationWallet = walletResolver.resolveDestinationWallet(userId, request);
        // Client fee quote can lag or under-size multi-input PSBTs; never reserve below server floor
        // or walletcreatefundedpsbt fails with PROVIDER_FINAL_FAILURE fee-cap.
        long networkFeeSats = resolveNetworkFeeReserve(request);
        applyQuote(tx, pricingService.quote(request.rail(), request.direction(), request.amountSats(), networkFeeSats));

        String proposalHash = proposalHash(tx, request);
        tx.setQuorumProposalHash(proposalHash);

        boolean requiresReserve = walletResolver.requiresSourceReserve(request);
        SettlementGateResult gate = binarySettlementGate.evaluateAndRequirePass(
                new SettlementGateCommand(
                        userId,
                        tx.getId(),
                        sourceWallet != null ? sourceWallet.getId() : request.sourceWalletId(),
                        request.idempotencyKey(),
                        true,
                        request.rail(),
                        request.direction(),
                        request.amountSats(),
                        networkFeeSats,
                        tx.getTotalDebitSats(),
                        requiresReserve,
                        proposalHash));

        stateMachine.transition(tx, KfeTransactionStatus.QUORUM_SYNC, "KFE_TRANSACTION_QUORUM_SYNC",
                Map.of(
                        "proposalHash", proposalHash,
                        "settlementGatePassed", 1,
                        "quorumAckCount", gate.quorumAckCount()));
        tx.setQuorumAckCount(gate.quorumAckCount());
        return new PreparedTransaction(
                tx, sourceWallet, destinationWallet, proposalHash, gate.quorumAckCount());
    }

    private WalletLock reserveAndLock(KfeSubmitTransactionRequest request, PreparedTransaction prepared) {
        KfeTransactionEntity tx = prepared.tx();
        KfeWalletEntity sourceWallet = prepared.sourceWallet();
        if (walletResolver.requiresSourceReserve(request)) {
            balanceService.reserve(sourceWallet.getId(), ASSET_BTC, tx.getTotalDebitSats());
            movementRecorder.record(tx.getId(), sourceWallet.getId(), "RESERVE", tx.getTotalDebitSats(), "AVAILABLE", "LOCKED");
        }
        // V_LIQUIDEZ: hold platform LN capacity until HTLC resolves (same TX as user reserve).
        if (request.rail() == KfeRail.LIGHTNING && request.direction() == KfeDirection.OUTBOUND) {
            lightningLiquidityService.reserveForTransaction(tx.getId(), tx.getTotalDebitSats());
        }
        stateMachine.transition(tx, KfeTransactionStatus.LOCKED, "KFE_TRANSACTION_LOCKED",
                Map.of("proposalHash", prepared.proposalHash(), "quorumAckCount", prepared.quorumAckCount()));
        return new WalletLock(sourceWallet, prepared.destinationWallet());
    }

    /**
     * @return outbox id when Lightning or On-chain outbound was enqueued (for sync-on-submit);
     *     null for internal settlement
     */
    private UUID routeLockedTransaction(
            Long userId,
            KfeSubmitTransactionRequest request,
            PreparedTransaction prepared,
            WalletLock lock) {
        KfeTransactionEntity tx = prepared.tx();
        if (request.rail() == KfeRail.INTERNAL || request.direction() == KfeDirection.INTERNAL) {
            settleInternal(userId, tx, lock.sourceWallet(), lock.destinationWallet());
            return null;
        }
        UUID outboxId = outboxUseCase.enqueueExternal(tx, request);
        stateMachine.transition(tx, KfeTransactionStatus.EXECUTING, "KFE_TRANSACTION_EXECUTING",
                Map.of("proposalHash", prepared.proposalHash(), "rail", tx.getRail().name()));
        // Ensure transactions_master row is visible to statement FK before insert.
        transactionRepository.saveAndFlush(tx);
        statementRecorder.record(userId, tx, lock.statementWalletId(tx), request);

        notificationPort.notifyPaymentInitiated(
                userId,
                tx.getId(),
                lock.statementWalletId(tx),
                tx.getRail().name(),
                tx.getGrossAmountSats());

        maybeNotifyVaultMesh(tx, request);

        if (request.direction() == KfeDirection.OUTBOUND
                && (request.rail() == KfeRail.LIGHTNING || request.rail() == KfeRail.ONCHAIN)) {
            return outboxId;
        }
        return null;
    }

    /**
     * Optional dual-path notify (default off). Does not replace rail executors / mpc-sidecar.
     */
    private void maybeNotifyVaultMesh(KfeTransactionEntity tx, KfeSubmitTransactionRequest request) {
        if (!vaultMeshIntentService.isSubmitOnOutboundEnabled()) {
            return;
        }
        if (request.direction() != KfeDirection.OUTBOUND) {
            return;
        }
        // On-chain mesh-only spends are Intent-gated in /v1/bitcoin/sign-psbt; skip DTO-hash
        // notify to avoid anti-nonce collisions with the PSBT session.
        if (request.rail() == KfeRail.ONCHAIN && vaultMeshIntentService.isMeshOnly()) {
            return;
        }
        try {
            vaultMeshIntentService.submitOutboundIntent(
                    tx.getId(),
                    tx.getExternalReference(),
                    tx.getGrossAmountSats(),
                    tx.getId() == null ? "" : tx.getId().toString());
        } catch (RuntimeException ex) {
            log.warn("vault_mesh_intent_notify_failed txId={} reason={}", tx.getId(), ex.toString());
        }
    }

    private KfeTransactionResponse completePublishAndRespond(
            Long userId,
            KfeIdempotencyEntity idempotency,
            KfeTransactionEntity tx,
            KfeWalletEntity destinationWallet) {
        idempotencyUseCase.complete(idempotency, tx);
        publishDashboards(userId, destinationWallet);
        return responseMapper.toTransactionResponse(transactionRepository.save(tx));
    }

    private KfeTransactionEntity createIntent(Long userId, KfeSubmitTransactionRequest request) {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(userId);
        tx.setIdempotencyKey(request.idempotencyKey());
        tx.setRail(request.rail());
        tx.setDirection(request.direction());
        tx.setSourceWalletId(request.sourceWalletId());
        tx.setDestinationWalletId(request.destinationWalletId());
        tx.setExternalReference(transactionReference(request));
        tx.setMemo(clean(request.memo()));
        tx.setGrossAmountSats(request.amountSats());
        return transactionRepository.save(tx);
    }

    private void settleInternal(
            Long userId,
            KfeTransactionEntity tx,
            KfeWalletEntity sourceWallet,
            KfeWalletEntity destinationWallet) {
        // Internal settlement is the only submit path that moves both sides immediately.
        balanceService.settleReservedDebit(sourceWallet.getId(), ASSET_BTC, tx.getTotalDebitSats());
        movementRecorder.record(tx.getId(), sourceWallet.getId(), "SETTLE_DEBIT", tx.getTotalDebitSats(), "LOCKED", null);
        balanceService.creditAvailable(destinationWallet.getId(), ASSET_BTC, tx.getReceiverAmountSats());
        movementRecorder.record(tx.getId(), destinationWallet.getId(), "CREDIT", tx.getReceiverAmountSats(), null, "AVAILABLE");
        stateMachine.transition(tx, KfeTransactionStatus.SETTLED, "KFE_TRANSACTION_SETTLED",
                Map.of("rail", tx.getRail().name()));
        feeSettlementService.creditKeroseneFee(tx);

        statementRecorder.record(userId, tx, sourceWallet.getId(), null);
        notificationPort.notifyInternalTransferSent(
                userId, tx.getId(), sourceWallet.getId(), tx.getTotalDebitSats());

        if (!destinationWallet.getUserId().equals(userId)) {
            statementRecorder.record(destinationWallet.getUserId(), tx, destinationWallet.getId(), null);
            notificationPort.notifyInternalTransferReceived(
                    destinationWallet.getUserId(), tx.getId(), destinationWallet.getId(), tx.getReceiverAmountSats());
        }
    }

    private long resolveNetworkFeeReserve(KfeSubmitTransactionRequest request) {
        long clientFee = Math.max(0L, request.networkFeeSats());
        if (request.rail() != KfeRail.ONCHAIN || request.direction() != KfeDirection.OUTBOUND) {
            return clientFee;
        }
        long floor = networkFeeEstimateService.reservedFeeFloorSats(
                request.feeRateSatPerVbyte(),
                request.feeTargetBlocks());
        if (clientFee < floor) {
            log.warn(
                    "[KFE Submit] raising on-chain fee reserve clientFeeSats={} floorFeeSats={} feeRate={} targetBlocks={}",
                    clientFee,
                    floor,
                    request.feeRateSatPerVbyte(),
                    request.feeTargetBlocks());
            return floor;
        }
        return clientFee;
    }

    private void applyQuote(KfeTransactionEntity tx, KfePricingService.Quote quote) {
        tx.setGrossAmountSats(quote.grossAmountSats());
        tx.setReceiverAmountSats(quote.receiverAmountSats());
        tx.setNetworkFeeSats(quote.networkFeeSats());
        tx.setKeroseneFeeSats(quote.keroseneFeeSats());
        tx.setTotalDebitSats(quote.totalDebitSats());
        tx.setPricingPolicyVersion(quote.pricingPolicyVersion());
        applyDisplaySnapshot(tx);
        transactionRepository.save(tx);
    }

    private void applyDisplaySnapshot(KfeTransactionEntity tx) {
        BigDecimal amountBtc = BigDecimal.valueOf(tx.getReceiverAmountSats())
                .divide(SATS_PER_BTC, 8, RoundingMode.HALF_UP);
        BigDecimal btcUsd = tickerPort.getPrice("usd");
        BigDecimal btcEur = tickerPort.getPrice("eur");
        BigDecimal btcBrl = tickerPort.getPrice("brl");

        tx.setDisplayBtcUsd(btcUsd);
        tx.setDisplayBtcEur(btcEur);
        tx.setDisplayBtcBrl(btcBrl);
        tx.setDisplayAmountUsd(convertSnapshot(amountBtc, btcUsd));
        tx.setDisplayAmountEur(convertSnapshot(amountBtc, btcEur));
        tx.setDisplayAmountBrl(convertSnapshot(amountBtc, btcBrl));
    }

    private BigDecimal convertSnapshot(BigDecimal amountBtc, BigDecimal btcPrice) {
        if (btcPrice == null || btcPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return amountBtc.multiply(btcPrice).setScale(2, RoundingMode.HALF_UP);
    }

    private String proposalHash(KfeTransactionEntity tx, KfeSubmitTransactionRequest request) {
        return hashService.sha256(String.join("|",
                "KFE_TX_PROPOSAL",
                tx.getId().toString(),
                tx.getUserId().toString(),
                tx.getRail().name(),
                tx.getDirection().name(),
                String.valueOf(tx.getSourceWalletId()),
                String.valueOf(tx.getDestinationWalletId()),
                String.valueOf(tx.getGrossAmountSats()),
                String.valueOf(tx.getReceiverAmountSats()),
                String.valueOf(tx.getNetworkFeeSats()),
                String.valueOf(tx.getKeroseneFeeSats()),
                String.valueOf(tx.getTotalDebitSats()),
                safe(request.externalReference()),
                safe(request.paymentRequestPublicId())));
    }

    private void publishDashboards(Long userId, KfeWalletEntity destinationWallet) {
        dashboardPublisher.publishAfterCommit(userId);
        if (destinationWallet != null && !destinationWallet.getUserId().equals(userId)) {
            dashboardPublisher.publishAfterCommit(destinationWallet.getUserId());
        }
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private String clean(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private String transactionReference(KfeSubmitTransactionRequest request) {
        String paymentRequestPublicId = clean(request.paymentRequestPublicId());
        return paymentRequestPublicId != null
                ? paymentRequestPublicId
                : clean(request.externalReference());
    }

    private record SubmissionAttempt(
            String requestHash,
            KfeIdempotencyEntity idempotency,
            KfeTransactionResponse existingResponse) {

        private static SubmissionAttempt reserved(String requestHash, KfeIdempotencyEntity idempotency) {
            return new SubmissionAttempt(requestHash, idempotency, null);
        }

        private static SubmissionAttempt existing(String requestHash, KfeTransactionResponse existingResponse) {
            return new SubmissionAttempt(requestHash, null, existingResponse);
        }
    }

    /**
     * @param lightningOutboxId non-null when LIGHTNING OUTBOUND should drain sync
     * @param onchainOutboxId non-null when ONCHAIN OUTBOUND should drain sync
     */
    private record SubmissionOutcome(
            KfeTransactionResponse response,
            UUID transactionId,
            UUID lightningOutboxId,
            UUID onchainOutboxId) {
    }

    private record PreparedTransaction(
            KfeTransactionEntity tx,
            KfeWalletEntity sourceWallet,
            KfeWalletEntity destinationWallet,
            String proposalHash,
            int quorumAckCount) {
    }

    private record WalletLock(KfeWalletEntity sourceWallet, KfeWalletEntity destinationWallet) {

        private UUID statementWalletId(KfeTransactionEntity tx) {
            return sourceWallet != null ? sourceWallet.getId() : tx.getDestinationWalletId();
        }
    }
}
