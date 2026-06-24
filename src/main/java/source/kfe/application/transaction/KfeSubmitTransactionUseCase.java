package source.kfe.application.transaction;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.common.financial.FinancialTickerPort;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.dto.KfeTransactionResponse;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeIdempotencyEntity;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.service.KfeBalanceService;
import source.kfe.service.KfeDashboardPublisher;
import source.kfe.service.KfeHashService;
import source.kfe.service.KfePricingService;
import source.kfe.service.KfeQuorumGateway;
import source.kfe.service.KfeResponseMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

@Service
public class KfeSubmitTransactionUseCase {

    private static final String ASSET_BTC = "BTC";
    private static final BigDecimal SATS_PER_BTC = new BigDecimal("100000000");

    private final KfeTransactionRepository transactionRepository;
    private final KfePricingService pricingService;
    private final FinancialTickerPort tickerPort;
    private final KfeBalanceService balanceService;
    private final KfeQuorumGateway quorumGateway;
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

    public KfeSubmitTransactionUseCase(
            KfeTransactionRepository transactionRepository,
            KfePricingService pricingService,
            FinancialTickerPort tickerPort,
            KfeBalanceService balanceService,
            KfeQuorumGateway quorumGateway,
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
            KfeTransactionStatementRecorder statementRecorder) {
        this.transactionRepository = transactionRepository;
        this.pricingService = pricingService;
        this.tickerPort = tickerPort;
        this.balanceService = balanceService;
        this.quorumGateway = quorumGateway;
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
    }

    /**
     * Submits a KFE-only transaction inside one database transaction.
     *
     * The idempotency reservation must happen before the transaction intent is created: if reservation fails,
     * no transaction row, balance movement, outbox item, statement, or dashboard side effect should be emitted.
     */
    @Transactional
    public KfeTransactionResponse submit(Long userId, KfeSubmitTransactionRequest request) {
        return submit(userId, request, null);
    }

    @Transactional
    public KfeTransactionResponse submit(Long userId, KfeSubmitTransactionRequest request, String deviceHash) {
        request = walletResolver.resolveInternalDestinationReference(request);
        SubmissionAttempt attempt = validateAndReserve(userId, request, deviceHash);
        if (attempt.existingResponse() != null) {
            return attempt.existingResponse();
        }

        KfeTransactionEntity tx = createIntent(userId, request);
        stateMachine.audit(tx, "KFE_TRANSACTION_INTENT", null, tx.getStatus(), null);

        PreparedTransaction prepared = validateQuoteAndQuorum(userId, tx, request, attempt.requestHash());
        WalletLock lock = reserveAndLock(request, prepared);
        routeLockedTransaction(userId, request, prepared, lock);

        return completePublishAndRespond(userId, attempt.idempotency(), prepared.tx(), lock.destinationWallet());
    }

    private SubmissionAttempt validateAndReserve(Long userId, KfeSubmitTransactionRequest request, String deviceHash) {
        validator.validate(request);
        walletResolver.requireNotSelfPayment(userId, request);
        authorizationUseCase.authorize(userId, request, deviceHash);

        String requestHash = idempotencyUseCase.requestHash(userId, request);
        KfeIdempotencyEntity existingIdempotency = idempotencyUseCase.find(userId, request.idempotencyKey());
        if (existingIdempotency != null) {
            return SubmissionAttempt.existing(requestHash, idempotencyUseCase.existingResponse(existingIdempotency, requestHash));
        }

        KfeIdempotencyEntity idempotency;
        try {
            idempotency = idempotencyUseCase.reserve(userId, request, requestHash);
        } catch (DataIntegrityViolationException | ConstraintViolationException ex) {
            return SubmissionAttempt.existing(requestHash,
                    idempotencyUseCase.getExistingByIdempotency(userId, request.idempotencyKey(), requestHash));
        }
        return SubmissionAttempt.reserved(requestHash, idempotency);
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
        stateMachine.transition(tx, KfeTransactionStatus.QUORUM_SYNC, "KFE_TRANSACTION_QUORUM_SYNC",
                Map.of("proposalHash", proposalHash));
        KfeQuorumGateway.Result quorum = quorumGateway.requireHealthyUnanimousConsensus(proposalHash);
        tx.setQuorumAckCount(quorum.acceptedNodes());
        return new PreparedTransaction(tx, sourceWallet, destinationWallet, proposalHash, quorum.acceptedNodes());
    }

    private WalletLock reserveAndLock(KfeSubmitTransactionRequest request, PreparedTransaction prepared) {
        KfeTransactionEntity tx = prepared.tx();
        KfeWalletEntity sourceWallet = prepared.sourceWallet();
        if (walletResolver.requiresSourceReserve(request)) {
            balanceService.reserve(sourceWallet.getId(), ASSET_BTC, tx.getTotalDebitSats());
            movementRecorder.record(tx.getId(), sourceWallet.getId(), "RESERVE", tx.getTotalDebitSats(), "AVAILABLE", "LOCKED");
        }
        stateMachine.transition(tx, KfeTransactionStatus.LOCKED, "KFE_TRANSACTION_LOCKED",
                Map.of("proposalHash", prepared.proposalHash(), "quorumAckCount", prepared.quorumAckCount()));
        return new WalletLock(sourceWallet, prepared.destinationWallet());
    }

    private void routeLockedTransaction(
            Long userId,
            KfeSubmitTransactionRequest request,
            PreparedTransaction prepared,
            WalletLock lock) {
        KfeTransactionEntity tx = prepared.tx();
        if (request.rail() == KfeRail.INTERNAL || request.direction() == KfeDirection.INTERNAL) {
            settleInternal(userId, tx, lock.sourceWallet(), lock.destinationWallet());
        } else {
            outboxUseCase.enqueueExternal(tx, request);
            stateMachine.transition(tx, KfeTransactionStatus.EXECUTING, "KFE_TRANSACTION_EXECUTING",
                    Map.of("proposalHash", prepared.proposalHash(), "rail", tx.getRail().name()));
            statementRecorder.record(userId, tx, lock.statementWalletId(tx), request);
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

        statementRecorder.record(userId, tx, sourceWallet.getId(), null);
        if (!destinationWallet.getUserId().equals(userId)) {
            statementRecorder.record(destinationWallet.getUserId(), tx, destinationWallet.getId(), null);
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
                safe(request.externalReference())));
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
