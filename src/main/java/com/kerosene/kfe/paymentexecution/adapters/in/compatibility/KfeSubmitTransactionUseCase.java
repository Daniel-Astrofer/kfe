package com.kerosene.kfe.paymentexecution.adapters.in.compatibility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kerosene.kfe.paymentexecution.application.port.in.PreflightPaymentUseCase;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentSubmissionMapper;
import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CompletePaymentSubmissionUseCase;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentExecutionResultMapper;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentRequestLinkUseCase;
import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentRequestLinkCommand;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentFundsUseCase;
import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentFundsCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.RouteLockedPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.command.RouteLockedPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CreatePaymentIntentUseCase;
import com.kerosene.kfe.paymentexecution.application.command.CreatePaymentIntentCommand;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionCommandDispatcher;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentIdempotencyUseCase;
import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentIdempotencyCommand;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.UUID;
import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PreparePaymentSubmissionUseCase;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;

@Service
public class KfeSubmitTransactionUseCase {

    private static final Logger log = LoggerFactory.getLogger(KfeSubmitTransactionUseCase.class);
    private static final String SYNC_WORKER_ID = "kfe-submit-sync-lightning";

    private final KfeTransactionRepository transactionRepository;
    private final PreparePaymentSubmissionUseCase prepareSubmission;
    private final ReservePaymentFundsUseCase reservePaymentFunds;
    private final KfeResponseMapper responseMapper;
    private final CompletePaymentSubmissionUseCase completeSubmission;
    private final PreflightPaymentUseCase preflight;
    private final ReservePaymentIdempotencyUseCase reserveIdempotency;
    private final PaymentExecutionLifecycleUseCase executionLifecycle;
    private final RouteLockedPaymentUseCase routePayment;
    private final CreatePaymentIntentUseCase createPaymentIntent;
    private final PaymentRequestLinkUseCase paymentRequestLinks;
    private final ExecutionCommandDispatcher commandDispatcher;
    private final boolean lightningSyncOnSubmit;
    private final boolean onchainSyncOnSubmit;
    private final TransactionTemplate transactionTemplate;

    public KfeSubmitTransactionUseCase(
            KfeTransactionRepository transactionRepository,
            PreparePaymentSubmissionUseCase prepareSubmission,
            ReservePaymentFundsUseCase reservePaymentFunds,
            KfeResponseMapper responseMapper,
            CompletePaymentSubmissionUseCase completeSubmission,
            PreflightPaymentUseCase preflight,
            ReservePaymentIdempotencyUseCase reserveIdempotency,
            PaymentExecutionLifecycleUseCase executionLifecycle,
            RouteLockedPaymentUseCase routePayment,
            CreatePaymentIntentUseCase createPaymentIntent,
            PaymentRequestLinkUseCase paymentRequestLinks,
            ExecutionCommandDispatcher commandDispatcher,
            @Value("${kfe.execution.lightning.sync-on-submit:true}") boolean lightningSyncOnSubmit,
            @Value("${kfe.execution.onchain.sync-on-submit:true}") boolean onchainSyncOnSubmit,
            PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.prepareSubmission = prepareSubmission;
        this.reservePaymentFunds = reservePaymentFunds;
        this.responseMapper = responseMapper;
        this.completeSubmission = completeSubmission;
        this.preflight = preflight;
        this.reserveIdempotency = reserveIdempotency;
        this.executionLifecycle = executionLifecycle;
        this.routePayment = routePayment;
        this.createPaymentIntent = createPaymentIntent;
        this.paymentRequestLinks = paymentRequestLinks;
        this.commandDispatcher = commandDispatcher;
        this.lightningSyncOnSubmit = lightningSyncOnSubmit;
        this.onchainSyncOnSubmit = onchainSyncOnSubmit;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
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
     * or claim races. On-chain also supports optional post-commit immediate dispatch.
     */
    public KfeTransactionResponse submit(Long userId, KfeSubmitTransactionRequest request) {
        return submit(userId, request, null);
    }

    public KfeTransactionResponse submit(Long userId, KfeSubmitTransactionRequest request, String deviceHash) {
        // This boundary owns the commit: joining an outer transaction would dispatch before its real commit.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("KFE transaction submission must start outside an existing transaction.");
        }
        var prepared = preflight.preflight(LegacyPaymentSubmissionMapper.toCommand(userId, request, deviceHash));
        if (prepared.existingPayment().isPresent()) {
            return LegacyPaymentExecutionResultMapper.toLegacyResponse(prepared.existingPayment().orElseThrow());
        }

        final KfeSubmitTransactionRequest authorizedRequest = LegacyPaymentSubmissionMapper.toLegacyRequest(prepared.command());
        final String requestHash = prepared.fingerprint().value();
        SubmissionOutcome outcome = transactionTemplate.execute(
                status -> submitAuthorized(userId, authorizedRequest, requestHash));
        if (outcome == null || outcome.response() == null) {
            throw new IllegalStateException("KFE transaction submission returned no response.");
        }

        if (outcome.lightningOutboxId() != null && lightningSyncOnSubmit) {
            return drainOutboxSync(userId, outcome, "Lightning");
        }
        // Broadcast on-chain in the request path so platform peer inbound can be exposed
        // immediately (recipient history + push) instead of waiting for the async worker.
        if (outcome.onchainOutboxId() != null && onchainSyncOnSubmit) {
            return drainOutboxSync(userId, outcome, "Onchain");
        }
        return outcome.response();
    }

    private SubmissionOutcome submitAuthorized(
            Long userId,
            KfeSubmitTransactionRequest request,
            String requestHash) {
        var reservation = reserveIdempotency.reserve(new ReservePaymentIdempotencyCommand(userId,
                new IdempotencyKey(request.idempotencyKey()), new RequestFingerprint(requestHash)));
        if (!reservation.reserved()) {
            return new SubmissionOutcome(LegacyPaymentExecutionResultMapper.toLegacyResponse(reservation.existingPayment()), null, null, null);
        }

        var paymentRequest = paymentRequestLinks.prepare(new PreparePaymentRequestLinkCommand(userId,
                PaymentRail.valueOf(request.rail().name()), PaymentDirection.valueOf(request.direction().name()),
                request.destinationWalletId(), request.amountSats(), request.paymentRequestPublicId()));

        PaymentExecutionId intentId = createPaymentIntent.create(new CreatePaymentIntentCommand(
                userId, new IdempotencyKey(request.idempotencyKey()), PaymentRail.valueOf(request.rail().name()),
                PaymentDirection.valueOf(request.direction().name()), request.sourceWalletId(),
                request.destinationWalletId(), request.amountSats(), request.externalReference(),
                request.memo(), request.paymentRequestPublicId()));
        KfeTransactionEntity tx = transactionRepository.findByIdAndUserId(intentId.value(), userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        executionLifecycle.recordCurrentState(
                new PaymentExecutionId(tx.getId()),
                ExecutionStatus.valueOf(tx.getStatus().name()),
                "KFE_TRANSACTION_INTENT",
                null);

        prepareSubmission.prepare(new PreparePaymentSubmissionCommand(userId, intentId,
                new RequestFingerprint(requestHash), request.networkFeeSats(), request.feeRateSatPerVbyte(),
                request.feeTargetBlocks(), request.externalReference(), request.paymentRequestPublicId()));
        var reserved = reservePaymentFunds.reserve(new ReservePaymentFundsCommand(userId, intentId));
        tx.setStatus(KfeTransactionStatus.valueOf(reserved.currentStatus().name()));
        var routed = routePayment.route(new RouteLockedPaymentCommand(userId, intentId,
                request.externalReference(), request.memo(), request.feeRateSatPerVbyte(), request.feeTargetBlocks()));
        tx.setStatus(KfeTransactionStatus.valueOf(routed.transition().currentStatus().name()));
        paymentRequest.ifPresent(link -> paymentRequestLinks.complete(new CompletePaymentRequestLinkCommand(userId, link, intentId)));

        KfeTransactionResponse response = LegacyPaymentExecutionResultMapper.toLegacyResponse(
                completeSubmission.complete(new CompletePaymentSubmissionCommand(userId, intentId,
                        new IdempotencyKey(request.idempotencyKey()), new RequestFingerprint(requestHash))));
        UUID lightningOutboxId =
                routed.rail() == PaymentRail.LIGHTNING ? routed.immediateDispatchOutboxId() : null;
        UUID onchainOutboxId =
                routed.rail() == PaymentRail.ONCHAIN ? routed.immediateDispatchOutboxId() : null;
        return new SubmissionOutcome(response, tx.getId(), lightningOutboxId, onchainOutboxId);
    }

    /**
     * After the ledger TX commits: claim the outbox item and run provider execution now
     * (Lightning pay or on-chain broadcast). Reloads the transaction for the HTTP response.
     */
    private KfeTransactionResponse drainOutboxSync(Long userId, SubmissionOutcome outcome, String railLabel) {
        UUID outboxId = outcome.lightningOutboxId() != null
                ? outcome.lightningOutboxId()
                : outcome.onchainOutboxId();
        UUID transactionId = outcome.transactionId();
        try {
            ExecutionCommandDispatcher.DispatchResult dispatch =
                    commandDispatcher.dispatchImmediately(outboxId, SYNC_WORKER_ID);
            if (dispatch == ExecutionCommandDispatcher.DispatchResult.ALREADY_CLAIMED) {
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
        return transactionRepository.findByIdAndUserId(transactionId, userId)
                .filter(tx -> transactionId.equals(tx.getId()) && userId.equals(tx.getUserId()))
                .map(responseMapper::toTransactionResponse)
                .orElse(outcome.response());
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

}
