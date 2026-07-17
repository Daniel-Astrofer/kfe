package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import source.common.financial.FinancialNotificationPort;
import source.kfe.application.transaction.KfeBalanceMovementRecorder;
import source.kfe.application.transaction.KfeLedgerMovementTypes;
import source.kfe.application.transaction.KfePlatformOnchainDestinationRouter;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.repository.KfeBalanceMovementRepository;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Instantly surfaces an <b>inbound</b> history row + push for the recipient when a Kerosene
 * user sends on-chain to another platform address (custodial/cold sink).
 *
 * <p>Deposit UTXO polling alone is too slow / racy for "na hora" UX: the recipient app stayed
 * blank until a later observe cycle. This path creates the inbound from the known outbound
 * broadcast (txid + amount) and lets the custodial observer only reconcile confs later.
 */
@Service
public class KfePlatformPeerInboundService {

    private static final Logger log = LoggerFactory.getLogger(KfePlatformPeerInboundService.class);
    public static final String PROVIDER = "PLATFORM_PEER_ONCHAIN";
    private static final String ASSET_BTC = "BTC";

    private final KfePlatformOnchainDestinationRouter destinationRouter;
    private final KfeWalletRepository walletRepository;
    private final KfeTransactionRepository transactionRepository;
    private final KfeBalanceMovementRepository movementRepository;
    private final KfeBalanceService balanceService;
    private final KfeBalanceMovementRecorder movementRecorder;
    private final KfePricingService pricingService;
    private final KfeFeeSettlementService feeSettlementService;
    private final KfeStatementService statementService;
    private final KfeResponseMapper responseMapper;
    private final KfeDashboardPublisher dashboardPublisher;
    private final KfeAuditLogService auditLogService;
    private final ObjectProvider<FinancialNotificationPort> notificationPort;
    private final int minConfirmations;

    public KfePlatformPeerInboundService(
            KfePlatformOnchainDestinationRouter destinationRouter,
            KfeWalletRepository walletRepository,
            KfeTransactionRepository transactionRepository,
            KfeBalanceMovementRepository movementRepository,
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder,
            KfePricingService pricingService,
            KfeFeeSettlementService feeSettlementService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            KfeAuditLogService auditLogService,
            ObjectProvider<FinancialNotificationPort> notificationPort,
            @Value(
                    "${kfe.custodial-deposit-observation.min-confirmations:${bitcoin.min-confirmations:3}}")
                    int minConfirmations) {
        this.destinationRouter = destinationRouter;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.movementRepository = movementRepository;
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
        this.pricingService = pricingService;
        this.feeSettlementService = feeSettlementService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.auditLogService = auditLogService;
        this.notificationPort = notificationPort;
        this.minConfirmations = Math.max(0, minConfirmations);
    }

    /**
     * Called after a successful on-chain broadcast when destination may be a platform wallet.
     *
     * <p>{@link Propagation#REQUIRES_NEW}: must not join the caller's completed after-commit
     * synchronization (that leaves a bound EntityManager with no live TX and fails with
     * "no transaction is in progress").
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void exposeAfterOutboundBroadcast(KfeTransactionEntity outbound) {
        if (outbound == null
                || outbound.getRail() != KfeRail.ONCHAIN
                || outbound.getDirection() != KfeDirection.OUTBOUND) {
            return;
        }
        String address = outbound.getExternalReference() != null
                ? outbound.getExternalReference().trim()
                : "";
        String txid = outbound.getBlockchainTxid() != null
                ? outbound.getBlockchainTxid().trim().toLowerCase(Locale.ROOT)
                : "";
        if (address.isEmpty() || txid.isEmpty()) {
            return;
        }

        Optional<UUID> sinkWalletId = destinationRouter.findPlatformSinkWalletIdForAddress(address);
        if (sinkWalletId.isEmpty()) {
            // Address may still be platform INTERNAL that was not rewritten — resolve owner sink.
            sinkWalletId = destinationRouter.resolveRecipientOnchainSinkWalletId(address);
        }
        if (sinkWalletId.isEmpty()) {
            return;
        }

        KfeWalletEntity sink = walletRepository.findById(sinkWalletId.get()).orElse(null);
        if (sink == null || sink.getUserId() == null) {
            return;
        }
        // Do not mirror sender's own wallet as inbound for self-sends.
        if (outbound.getUserId() != null && outbound.getUserId().equals(sink.getUserId())
                && outbound.getSourceWalletId() != null
                && outbound.getSourceWalletId().equals(sink.getId())) {
            return;
        }

        long amountSats = Math.max(0L, outbound.getReceiverAmountSats());
        if (amountSats <= 0L) {
            amountSats = Math.max(0L, outbound.getGrossAmountSats());
        }
        if (amountSats <= 0L) {
            return;
        }

        // Already have inbound for this chain tx on this user/wallet?
        List<KfeTransactionEntity> existing =
                transactionRepository.findByBlockchainTxidAndUserId(txid, sink.getUserId());
        for (KfeTransactionEntity row : existing) {
            if (row.getDirection() == KfeDirection.INBOUND
                    && sink.getId().equals(row.getDestinationWalletId())) {
                refreshExisting(row, sink, amountSats, outbound.getConfirmations());
                return;
            }
        }

        String idempotencyKey = "platform-peer-in:" + outbound.getId();
        if (transactionRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return;
        }

        KfePricingService.Quote quote;
        try {
            quote = pricingService.quote(KfeRail.ONCHAIN, KfeDirection.INBOUND, amountSats, 0L);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Peer Inbound] quote failed sink={} amount={}: {}",
                    sink.getId(),
                    amountSats,
                    exception.getMessage());
            return;
        }

        int confs = Math.max(0, outbound.getConfirmations());
        boolean settleNow = confs >= minConfirmations;
        KfeTransactionStatus status =
                settleNow ? KfeTransactionStatus.SETTLED : KfeTransactionStatus.VALIDATING;

        KfeTransactionEntity inbound = new KfeTransactionEntity();
        inbound.setUserId(sink.getUserId());
        inbound.setIdempotencyKey(idempotencyKey);
        inbound.setRail(KfeRail.ONCHAIN);
        inbound.setDirection(KfeDirection.INBOUND);
        inbound.setDestinationWalletId(sink.getId());
        inbound.setSourceWalletId(outbound.getSourceWalletId());
        inbound.setExternalReference(address);
        inbound.setMemo("Recebido de usuário Kerosene (on-chain)");
        inbound.setGrossAmountSats(quote.grossAmountSats());
        inbound.setReceiverAmountSats(quote.receiverAmountSats());
        inbound.setNetworkFeeSats(0L);
        inbound.setKeroseneFeeSats(quote.keroseneFeeSats());
        inbound.setTotalDebitSats(0L);
        inbound.setProvider(PROVIDER);
        inbound.setProviderReference(outbound.getId().toString());
        inbound.setBlockchainTxid(txid);
        inbound.setConfirmations(confs);
        inbound.setStatus(status);
        inbound = transactionRepository.saveAndFlush(inbound);

        if (settleNow) {
            if (!alreadyCredited(inbound.getId())) {
                creditOnce(inbound.getId(), sink.getId(), quote.receiverAmountSats());
            }
            feeSettlementService.creditKeroseneFee(inbound);
            notifyConfirmed(sink, inbound, quote.receiverAmountSats(), confs);
        } else {
            notifyDetected(sink, inbound, quote.receiverAmountSats(), confs);
        }

        statementService.recordUserStatement(
                sink.getUserId(),
                sink.getId(),
                inbound,
                new LinkedHashMap<>(responseMapper.buildDisplayPayload(inbound, sink.getUserId())));
        dashboardPublisher.publishAfterCommit(sink.getUserId());

        try {
            // Reuse registered custodial audit types (unknown types abort the whole expose).
            auditLogService.record(
                    settleNow ? "KFE_INBOUND_SETTLED" : "KFE_INBOUND_CREDITED",
                    inbound.getId(),
                    sink.getId(),
                    null,
                    status,
                    Map.of(
                            "outboundTxId", outbound.getId().toString(),
                            "txid", txid,
                            "amountSats", quote.receiverAmountSats(),
                            "confirmations", confs,
                            "source", PROVIDER));
        } catch (RuntimeException auditFailure) {
            log.warn(
                    "[KFE Peer Inbound] audit skipped inboundId={}: {}",
                    inbound.getId(),
                    auditFailure.getMessage());
        }

        log.info(
                "[KFE Peer Inbound] exposed recipientUserId={} sinkWalletId={} outboundId={} txid={} amount={} status={}",
                sink.getUserId(),
                sink.getId(),
                outbound.getId(),
                txid,
                quote.receiverAmountSats(),
                status);
    }

    private void refreshExisting(
            KfeTransactionEntity existing, KfeWalletEntity sink, long amountSats, int confs) {
        boolean changed = false;
        if (confs > existing.getConfirmations()) {
            existing.setConfirmations(confs);
            changed = true;
        }
        if (amountSats > existing.getGrossAmountSats()) {
            existing.setGrossAmountSats(amountSats);
            existing.setReceiverAmountSats(Math.max(existing.getReceiverAmountSats(), amountSats));
            changed = true;
        }
        boolean canSettle = existing.getConfirmations() >= minConfirmations;
        if (canSettle && existing.getStatus() != KfeTransactionStatus.SETTLED) {
            existing.setStatus(KfeTransactionStatus.SETTLED);
            changed = true;
            if (!alreadyCredited(existing.getId())) {
                long credit = Math.max(existing.getReceiverAmountSats(), existing.getGrossAmountSats());
                if (credit > 0L && creditOnce(existing.getId(), sink.getId(), credit)) {
                    feeSettlementService.creditKeroseneFee(existing);
                    notifyConfirmed(sink, existing, credit, existing.getConfirmations());
                }
            }
        }
        if (changed) {
            transactionRepository.save(existing);
            statementService.recordUserStatement(
                    sink.getUserId(),
                    sink.getId(),
                    existing,
                    new LinkedHashMap<>(responseMapper.buildDisplayPayload(existing, sink.getUserId())));
            dashboardPublisher.publishAfterCommit(sink.getUserId());
        }
    }

    private boolean alreadyCredited(UUID transactionId) {
        return movementRepository.existsByTransactionIdAndMovementTypeIn(
                transactionId, KfeLedgerMovementTypes.USER_AVAILABLE_CREDIT_TYPES);
    }

    private boolean creditOnce(UUID transactionId, UUID walletId, long creditSats) {
        boolean wrote = movementRecorder.record(
                transactionId,
                walletId,
                KfeLedgerMovementTypes.CREDIT_CUSTODIAL_DEPOSIT,
                creditSats,
                null,
                "AVAILABLE");
        if (!wrote) {
            return false;
        }
        balanceService.creditAvailable(walletId, ASSET_BTC, creditSats);
        return true;
    }

    private void notifyDetected(KfeWalletEntity wallet, KfeTransactionEntity tx, long amount, int confs) {
        FinancialNotificationPort port = notificationPort.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            port.notifyDepositDetected(
                    wallet.getUserId(), tx.getId(), wallet.getId(), "ONCHAIN", amount, confs);
        } catch (RuntimeException exception) {
            log.warn("[KFE Peer Inbound] notify detected failed: {}", exception.getMessage());
        }
    }

    private void notifyConfirmed(KfeWalletEntity wallet, KfeTransactionEntity tx, long amount, int confs) {
        FinancialNotificationPort port = notificationPort.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            port.notifyDepositConfirmed(
                    wallet.getUserId(), tx.getId(), wallet.getId(), "ONCHAIN", amount, confs);
        } catch (RuntimeException exception) {
            log.warn("[KFE Peer Inbound] notify confirmed failed: {}", exception.getMessage());
        }
    }
}
