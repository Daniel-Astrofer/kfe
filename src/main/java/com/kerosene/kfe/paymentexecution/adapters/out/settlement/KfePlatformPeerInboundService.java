package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.kerosene.kfe.messaging.adapters.out.persistence.KfeFinancialNotificationOutboxService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.pricing.adapters.in.compatibility.KfePricingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.ledger.domain.KfeLedgerMovementTypes;
import com.kerosene.kfe.paymentexecution.adapters.out.rail.KfePlatformOnchainDestinationRouter;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

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
    private final KfePaymentRequestRepository paymentRequestRepository;
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

    @Autowired(required = false)
    private KfeFinancialNotificationOutboxService notificationOutbox;

    public KfePlatformPeerInboundService(
            KfePlatformOnchainDestinationRouter destinationRouter,
            KfeWalletRepository walletRepository,
            KfeTransactionRepository transactionRepository,
            KfeBalanceMovementRepository movementRepository,
            KfePaymentRequestRepository paymentRequestRepository,
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder,
            KfePricingService pricingService,
            KfeFeeSettlementService feeSettlementService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            KfeAuditLogService auditLogService,
            ObjectProvider<FinancialNotificationPort> notificationPort,
            KfeBitcoinFinalityPolicy finalityPolicy) {
        this.destinationRouter = destinationRouter;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.movementRepository = movementRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
        this.pricingService = pricingService;
        this.feeSettlementService = feeSettlementService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.auditLogService = auditLogService;
        this.notificationPort = notificationPort;
        this.minConfirmations = finalityPolicy.getCreditConfirmations();
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

        // Lock matching requests before touching inbound rows or balances, matching
        // cancellation's request -> transaction order. A cancelled request is excluded.
        List<KfePaymentRequestEntity> openRequests = paymentRequestRepository.findOpenByAddressAndRailForUpdate(
                address, KfePaymentRequestStatus.OPEN, KfeRail.ONCHAIN, sink.getUserId());

        // Already have inbound for this chain tx on this user/wallet?
        List<KfeTransactionEntity> existing =
                transactionRepository.findByBlockchainTxidAndUserId(txid, sink.getUserId());
        for (KfeTransactionEntity row : existing) {
            if (row.getDirection() == KfeDirection.INBOUND
                    && sink.getId().equals(row.getDestinationWalletId())) {
                refreshExisting(row, sink, amountSats, outbound.getConfirmations());
                // Still close any open QR/payment-request for this address (recipient UI polls that).
                closeMatchingPaymentRequests(openRequests, address, amountSats, row);
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

        // Close open receive QR / payment-request so the recipient screen leaves "Pendente".
        closeMatchingPaymentRequests(openRequests, address, amountSats, inbound);

        log.info(
                "[KFE Peer Inbound] exposed recipientUserId={} sinkWalletId={} outboundId={} txid={} amount={} status={}",
                sink.getUserId(),
                sink.getId(),
                outbound.getId(),
                txid,
                quote.receiverAmountSats(),
                status);
    }

    /**
     * Marks OPEN on-chain payment requests for {@code address} as PAID when a peer inbound lands.
     * Without this, funds credit via PLATFORM_PEER_ONCHAIN but the QR screen keeps polling OPEN
     * until the slower chain UTXO monitor catches up (often minutes / never in fee-edge cases).
     */
    private void closeMatchingPaymentRequests(
            List<KfePaymentRequestEntity> openRequests,
            String address, long observedSats, KfeTransactionEntity inbound) {
        if (address == null || address.isBlank() || inbound == null || inbound.getId() == null) {
            return;
        }
        if (openRequests.isEmpty()) {
            return;
        }
        KfePaymentRequestEntity selected = openRequests.stream()
                .filter(request -> matchesPaymentRequest(request, inbound, observedSats))
                .min((left, right) -> Integer.compare(paymentRequestMatchRank(left, observedSats),
                        paymentRequestMatchRank(right, observedSats)))
                .orElse(null);
        if (selected != null) {
            selected.markPaid(inbound.getId());
            paymentRequestRepository.save(selected);
            log.info(
                    "[KFE Peer Inbound] closed payment request publicId={} address={} inboundId={} observedSats={}",
                    selected.getPublicId(),
                    address,
                    inbound.getId(),
                    observedSats);
        }
        if (inbound.getUserId() != null) {
            dashboardPublisher.publishAfterCommit(inbound.getUserId());
        }
    }

    private boolean matchesPaymentRequest(
            KfePaymentRequestEntity request, KfeTransactionEntity inbound, long observedSats) {
        if (request.getStatus() != KfePaymentRequestStatus.OPEN) {
            return false;
        }
        if (request.getUserId() != null && inbound.getUserId() != null
                && !request.getUserId().equals(inbound.getUserId())) {
            return false;
        }
        Long requested = request.getAmountSats();
        if (requested == null || requested <= 0L || observedSats >= requested) {
            return true;
        }
        // Peer transfers can lose the platform fee before the receive-side row is created.
        long floor = requested - Math.max(requested / 100L, 500L);
        return observedSats >= Math.max(0L, floor);
    }

    private int paymentRequestMatchRank(KfePaymentRequestEntity request, long observedSats) {
        Long requested = request.getAmountSats();
        if (requested == null || requested <= 0L) return 2;
        return requested == observedSats ? 0 : 1;
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
        if (notificationOutbox != null) {
            notificationOutbox.enqueue(
                    notificationOutbox.stableEventId("DEPOSIT_DETECTED", tx.getId(), tx.getBlockchainTxid()),
                    wallet.getUserId(), tx.getId(), "DEPOSIT_DETECTED",
                    Map.of("walletId", wallet.getId(), "rail", "ONCHAIN", "amountSats", amount,
                            "confirmations", confs, "txid", tx.getBlockchainTxid()));
            return;
        }
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
        if (notificationOutbox != null) {
            notificationOutbox.enqueue(
                    notificationOutbox.stableEventId("DEPOSIT_CONFIRMED", tx.getId(), "settled"),
                    wallet.getUserId(), tx.getId(), "DEPOSIT_CONFIRMED",
                    Map.of("walletId", wallet.getId(), "rail", "ONCHAIN", "amountSats", amount,
                            "confirmations", confs, "txid", tx.getBlockchainTxid()));
            return;
        }
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
