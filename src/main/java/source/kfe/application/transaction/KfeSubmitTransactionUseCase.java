package source.kfe.application.transaction;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import source.common.financial.FinancialTickerPort;
import source.common.financial.FinancialNotificationPort;
import source.kfe.application.settlement.BinarySettlementGate;
import source.kfe.application.settlement.SettlementGateCommand;
import source.kfe.application.settlement.SettlementGateResult;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.dto.KfeTransactionResponse;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeIdempotencyEntity;
import source.kfe.model.KfePaymentRequestEntity;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.service.KfeBalanceService;
import source.kfe.service.KfeDashboardPublisher;
import source.kfe.service.KfeExecutionOutboxProcessor;
import source.kfe.service.KfeExecutionOutboxService;
import source.kfe.service.KfeFeeSettlementService;
import source.kfe.service.KfeHashService;
import source.kfe.service.KfeLightningLiquidityService;
import source.kfe.service.KfePricingService;
import source.kfe.service.KfeResponseMapper;

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
    private final boolean lightningSyncOnSubmit;
    private final TransactionTemplate transactionTemplate;

    public KfeSubmitTransactionUseCase(
            KfeTransactionRepository transactionRepository,
            KfePricingService pricingService,
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
            @Value("${kfe.execution.lightning.sync-on-submit:true}") boolean lightningSyncOnSubmit,
            PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.pricingService = pricingService;
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
        this.lightningSyncOnSubmit = lightningSyncOnSubmit;
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
            return drainLightningOutboxSync(outcome);
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
            return new SubmissionOutcome(existing, null, null);
        }

        KfePaymentRequestEntity paymentRequest = paymentRequestSettlementUseCase.lockAndValidate(request);

        KfeTransactionEntity tx = createIntent(userId, request);
        stateMachine.audit(tx, "KFE_TRANSACTION_INTENT", null, tx.getStatus(), null);

        PreparedTransaction prepared = validateQuoteAndQuorum(userId, tx, request, requestHash);
        WalletLock lock = reserveAndLock(request, prepared);
        UUID lightningOutboxId = routeLockedTransaction(userId, request, prepared, lock);
        paymentRequestSettlementUseCase.markPaid(paymentRequest, prepared.tx());

        KfeTransactionResponse response =
                completePublishAndRespond(userId, idempotency, prepared.tx(), lock.destinationWallet());
        return new SubmissionOutcome(response, prepared.tx().getId(), lightningOutboxId);
    }

    /**
     * After the ledger TX commits: claim the LIGHTNING_OUTBOUND outbox item and run LND pay now.
     * Reloads the transaction so the HTTP response reflects SETTLED / FAILED / still EXECUTING.
     */
    private KfeTransactionResponse drainLightningOutboxSync(SubmissionOutcome outcome) {
        UUID outboxId = outcome.lightningOutboxId();
        UUID transactionId = outcome.transactionId();
        try {
            boolean claimed = outboxService.claimImmediate(outboxId, SYNC_WORKER_ID);
            if (!claimed) {
                log.info(
                        "[KFE Submit] Lightning outbox already claimed (async worker) outboxId={} txId={}",
                        outboxId,
                        transactionId);
            } else {
                log.info(
                        "[KFE Submit] Lightning sync-on-submit drain starting outboxId={} txId={}",
                        outboxId,
                        transactionId);
                outboxProcessor.process(outboxId);
            }
        } catch (RuntimeException exception) {
            // Processor already marks retryable/final failure in most paths; never fail the HTTP
            // envelope if the intent was recorded — client can poll history.
            log.warn(
                    "[KFE Submit] Lightning sync drain error outboxId={} txId={}: {}",
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
        applyQuote(tx, pricingService.quote(request.rail(), request.direction(), request.amountSats(), request.networkFeeSats()));

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
                        request.networkFeeSats(),
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
     * @return outbox id when Lightning outbound was enqueued (for sync-on-submit); null otherwise
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
        statementRecorder.record(userId, tx, lock.statementWalletId(tx), request);

        notificationPort.notifyExternalPaymentSent(
                userId,
                tx.getId(),
                lock.statementWalletId(tx),
                tx.getRail().name(),
                tx.getGrossAmountSats());

        if (request.rail() == KfeRail.LIGHTNING && request.direction() == KfeDirection.OUTBOUND) {
            return outboxId;
        }
        return null;
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

    private void applyQuote(KfeTransactionEntity tx, KfePricingService.Quote quote) {
        tx.setGrossAmountSats(quote.grossAmountSats());
        tx.setReceiverAmountSats(quote.receiverAmountSats());
        tx.setNetworkFeeSats(quote.networkFeeSats());
        tx.setKeroseneFeeSats(quote.keroseneFeeSats());
        tx.setTotalDebitSats(quote.totalDebitSats());
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
     * @param lightningOutboxId non-null when LIGHTNING OUTBOUND was enqueued and should drain sync
     */
    private record SubmissionOutcome(
            KfeTransactionResponse response,
            UUID transactionId,
            UUID lightningOutboxId) {
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
