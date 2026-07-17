package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import source.common.financial.FinancialNotificationPort;
import source.kfe.application.transaction.KfeBalanceMovementRecorder;
import source.kfe.application.transaction.KfeLedgerMovementTypes;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletAddressStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.rail.BlockchainClient;
import source.kfe.repository.KfeBalanceMovementRepository;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Detects external on-chain deposits into {@link KfeWalletKind#CUSTODIAL_ONCHAIN} addresses
 * (e.g. Electrum → custodial receive address) and credits the internal ledger.
 *
 * <p>Policy: as soon as the tx is seen on the network (mempool / 0-conf), create an inbound
 * row with status {@code VALIDATING} (UI badge PENDING), notify the user, and publish
 * dashboard/WS so the recipient app updates immediately. {@code available_sats} is credited
 * only when confirmations reach {@code minConfirmations}.
 *
 * <p>Without this, only payment-request inbounds credit {@code available_sats}. Random chain
 * deposits only moved {@code observed_sats}, so the app balance looked stuck.
 */
@Service
@ConditionalOnProperty(
        name = "kfe.custodial-deposit-observation.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class KfeCustodialDepositObservationService {

    private static final Logger log = LoggerFactory.getLogger(KfeCustodialDepositObservationService.class);
    public static final String PROVIDER_CUSTODIAL_OBSERVER = "BITCOIN_CORE_CUSTODIAL_OBSERVER";
    private static final String ASSET_BTC = "BTC";

    private final KfeWalletRepository walletRepository;
    private final KfeWalletAddressRepository addressRepository;
    private final KfeTransactionRepository transactionRepository;
    private final KfeBalanceMovementRepository movementRepository;
    private final ObjectProvider<BlockchainClient> blockchainClient;
    private final KfeBalanceService balanceService;
    private final KfeBalanceMovementRecorder movementRecorder;
    private final KfePricingService pricingService;
    private final KfeFeeSettlementService feeSettlementService;
    private final KfeStatementService statementService;
    private final KfeResponseMapper responseMapper;
    private final KfeDashboardPublisher dashboardPublisher;
    private final KfeAuditLogService auditLogService;
    private final ObjectProvider<FinancialNotificationPort> notificationPort;
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int minConfirmations;

    public KfeCustodialDepositObservationService(
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            KfeTransactionRepository transactionRepository,
            KfeBalanceMovementRepository movementRepository,
            ObjectProvider<BlockchainClient> blockchainClient,
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder,
            KfePricingService pricingService,
            KfeFeeSettlementService feeSettlementService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            KfeAuditLogService auditLogService,
            ObjectProvider<FinancialNotificationPort> notificationPort,
            ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService,
            TransactionTemplate transactionTemplate,
            @Value("${kfe.custodial-deposit-observation.batch-size:30}") int batchSize,
            @Value(
                    "${kfe.custodial-deposit-observation.min-confirmations:${bitcoin.min-confirmations:3}}")
                    int minConfirmations) {
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.transactionRepository = transactionRepository;
        this.movementRepository = movementRepository;
        this.blockchainClient = blockchainClient;
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
        this.pricingService = pricingService;
        this.feeSettlementService = feeSettlementService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.auditLogService = auditLogService;
        this.notificationPort = notificationPort;
        this.onchainBalanceSyncService = onchainBalanceSyncService;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = Math.max(1, batchSize);
        this.minConfirmations = Math.max(0, minConfirmations);
    }

    @Scheduled(
            fixedDelayString = "${kfe.custodial-deposit-observation.fixed-delay-ms:20000}",
            initialDelayString = "${kfe.custodial-deposit-observation.initial-delay-ms:12000}")
    public void reconcileCustodialDeposits() {
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }
        // CUSTODIAL is the primary sink; also scan INTERNAL wallets that still hold
        // historical receive addresses so deposits are not invisible if routing missed.
        List<KfeWalletEntity> wallets = walletRepository.findByKindInAndStatus(
                List.of(KfeWalletKind.CUSTODIAL_ONCHAIN, KfeWalletKind.INTERNAL),
                KfeWalletStatus.ACTIVE);
        int limit = Math.min(batchSize, wallets.size());
        for (int i = 0; i < limit; i++) {
            try {
                observeWallet(wallets.get(i).getId());
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Custodial Deposit] failed walletId={}: {}",
                        wallets.get(i).getId(),
                        exception.getMessage());
            }
        }
    }

    /** Public entry for ZMQ / reactive path. */
    public void observeWallet(UUID walletId) {
        // Probe UTXOs outside a long TX, then commit each deposit independently so one
        // failure cannot roll back earlier credits.
        KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
        if (wallet == null || wallet.getStatus() != KfeWalletStatus.ACTIVE) {
            return;
        }
        // Ledger-credit sinks for on-chain deposits (not cold WATCH_ONLY — that path is cold observation).
        if (wallet.getKind() != KfeWalletKind.CUSTODIAL_ONCHAIN
                && wallet.getKind() != KfeWalletKind.INTERNAL) {
            return;
        }
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }

        Map<String, DepositAggregate> byTxid = collectDeposits(client, walletId);
        if (byTxid.isEmpty()) {
            return;
        }

        boolean changed = false;
        Long userId = wallet.getUserId();
        for (Map.Entry<String, DepositAggregate> entry : byTxid.entrySet()) {
            try {
                Boolean applied = transactionTemplate.execute(status -> {
                    KfeWalletEntity locked = walletRepository.findById(walletId).orElse(null);
                    if (locked == null) {
                        return false;
                    }
                    return upsertDeposit(locked, entry.getKey(), entry.getValue());
                });
                if (Boolean.TRUE.equals(applied)) {
                    changed = true;
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Custodial Deposit] deposit commit failed walletId={} txid={}: {}",
                        walletId,
                        entry.getKey(),
                        exception.getMessage(),
                        exception);
            }
        }
        if (changed) {
            resyncObserved(walletId);
            if (userId != null) {
                dashboardPublisher.publishAfterCommit(userId);
            }
        }
    }

    private Map<String, DepositAggregate> collectDeposits(BlockchainClient client, UUID walletId) {
        List<KfeWalletAddressEntity> addresses =
                addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId);
        Map<String, DepositAggregate> byTxid = new LinkedHashMap<>();
        for (KfeWalletAddressEntity row : addresses) {
            if (row.getStatus() != null && row.getStatus() != KfeWalletAddressStatus.ACTIVE) {
                continue;
            }
            String address = row.getAddress();
            if (address == null || address.isBlank()) {
                continue;
            }
            List<BlockchainClient.AddressUtxo> utxos;
            try {
                utxos = client.getUnspentOutputsMerged(address.trim());
            } catch (RuntimeException exception) {
                log.debug(
                        "[KFE Custodial Deposit] utxo probe failed address={}: {}",
                        address,
                        exception.getMessage());
                continue;
            }
            for (BlockchainClient.AddressUtxo utxo : utxos) {
                if (utxo == null || utxo.txid() == null || utxo.txid().isBlank() || utxo.valueSats() <= 0L) {
                    continue;
                }
                String txid = utxo.txid().trim().toLowerCase(Locale.ROOT);
                int confs = Math.max(0, utxo.confirmations());
                DepositAggregate agg = byTxid.computeIfAbsent(
                        txid, key -> new DepositAggregate(address.trim(), 0L, confs));
                agg.amountSats += utxo.valueSats();
                agg.confirmations = Math.max(agg.confirmations, confs);
                if (agg.sampleAddress == null || agg.sampleAddress.isBlank()) {
                    agg.sampleAddress = address.trim();
                }
            }
        }
        return byTxid;
    }

    private boolean upsertDeposit(KfeWalletEntity wallet, String txid, DepositAggregate deposit) {
        if (deposit.amountSats <= 0L) {
            return false;
        }
        // Always surface 0-conf (mempool) deposits as VALIDATING so the recipient
        // app sees the inbound immediately. available_sats is credited only when
        // confirmations >= minConfirmations (default 3 in prod / 1 local).
        // Previously confs < min returned false and the Linux app stayed blank
        // until 3 blocks — sender already saw the outbound.

        // Already known for this chain tx (payment-request or prior custodial observe).
        List<KfeTransactionEntity> existingForTx =
                transactionRepository.findByBlockchainTxidAndUserId(txid, wallet.getUserId());
        for (KfeTransactionEntity existing : existingForTx) {
            if (existing.getDirection() != KfeDirection.INBOUND) {
                continue;
            }
            if (!wallet.getId().equals(existing.getDestinationWalletId())) {
                continue;
            }
            // Update confs / settle when needed; credit only if no available credit yet.
            boolean patched = false;
            if (deposit.confirmations > existing.getConfirmations()) {
                existing.setConfirmations(deposit.confirmations);
                patched = true;
            }
            // Amount can grow if more outputs to same address appear in the same tx.
            if (deposit.amountSats > existing.getGrossAmountSats()) {
                existing.setGrossAmountSats(deposit.amountSats);
                existing.setReceiverAmountSats(Math.max(existing.getReceiverAmountSats(), deposit.amountSats));
                patched = true;
            }
            int settleAt = Math.max(0, minConfirmations);
            boolean canSettle = deposit.confirmations >= settleAt;
            if (existing.getStatus() != KfeTransactionStatus.SETTLED && canSettle) {
                existing.setStatus(KfeTransactionStatus.SETTLED);
                patched = true;
                if (!alreadyCreditedAvailable(existing.getId())) {
                    long creditSats = Math.max(0L, existing.getReceiverAmountSats());
                    if (creditSats <= 0L) {
                        creditSats = Math.max(0L, existing.getGrossAmountSats());
                    }
                    if (creditSats > 0L
                            && creditAvailableOnce(
                                    existing.getId(), wallet.getId(), creditSats)) {
                        feeSettlementService.creditKeroseneFee(existing);
                        notifyDeposit(wallet, existing, creditSats, deposit.confirmations);
                    }
                }
            }
            if (patched) {
                transactionRepository.save(existing);
                // Keep history row identity; only status/confs/updatedAt change.
                statementService.recordUserStatement(
                        wallet.getUserId(),
                        wallet.getId(),
                        existing,
                        new LinkedHashMap<>(responseMapper.buildDisplayPayload(existing, wallet.getUserId())));
                return true;
            }
            return false;
        }

        String idempotencyKey = "custodial-dep:" + wallet.getId() + ":" + txid;
        if (transactionRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return false;
        }

        KfePricingService.Quote quote;
        try {
            quote = pricingService.quote(
                    KfeRail.ONCHAIN, KfeDirection.INBOUND, deposit.amountSats, 0L);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Custodial Deposit] quote failed walletId={} amount={}: {}",
                    wallet.getId(),
                    deposit.amountSats,
                    exception.getMessage());
            return false;
        }

        int confs = deposit.confirmations;
        // minConfirmations=0 (local) → credit in mempool; production typically waits for N confs.
        boolean settleNow = confs >= minConfirmations;
        KfeTransactionStatus status =
                settleNow ? KfeTransactionStatus.SETTLED : KfeTransactionStatus.VALIDATING;

        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(wallet.getUserId());
        tx.setIdempotencyKey(idempotencyKey);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.INBOUND);
        tx.setDestinationWalletId(wallet.getId());
        tx.setExternalReference(deposit.sampleAddress);
        tx.setMemo(wallet.getKind() == KfeWalletKind.INTERNAL
                ? "Depósito on-chain (conta Kerosene)"
                : "Depósito on-chain (carteira custodial)");
        tx.setGrossAmountSats(quote.grossAmountSats());
        tx.setReceiverAmountSats(quote.receiverAmountSats());
        tx.setNetworkFeeSats(0L);
        tx.setKeroseneFeeSats(quote.keroseneFeeSats());
        tx.setTotalDebitSats(0L);
        tx.setProvider(PROVIDER_CUSTODIAL_OBSERVER);
        tx.setProviderReference(txid);
        tx.setBlockchainTxid(txid);
        tx.setConfirmations(confs);
        tx.setStatus(status);
        tx = transactionRepository.save(tx);

        if (settleNow) {
            if (!alreadyCreditedAvailable(tx.getId())) {
                creditAvailableOnce(tx.getId(), wallet.getId(), quote.receiverAmountSats());
            }
            feeSettlementService.creditKeroseneFee(tx);
            notifyDeposit(wallet, tx, quote.receiverAmountSats(), confs);
        } else {
            notifyDetected(wallet, tx, quote.receiverAmountSats(), confs);
        }

        Map<String, Object> statement =
                new LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, wallet.getUserId()));
        // Upsert: first sight creates row; conf/status later refresh same transactionId.
        statementService.recordUserStatement(wallet.getUserId(), wallet.getId(), tx, statement);

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("txid", txid);
        audit.put("observedSats", deposit.amountSats);
        audit.put("creditedSats", quote.receiverAmountSats());
        audit.put("confirmations", confs);
        audit.put("address", deposit.sampleAddress == null ? "" : deposit.sampleAddress);
        auditLogService.record(
                settleNow ? "KFE_INBOUND_SETTLED" : "KFE_INBOUND_CREDITED",
                tx.getId(),
                wallet.getId(),
                null,
                status,
                audit);

        log.info(
                "[KFE Custodial Deposit] exposed walletId={} txid={} observedSats={} creditedSats={} confs={} status={} settleNow={}",
                wallet.getId(),
                txid,
                deposit.amountSats,
                quote.receiverAmountSats(),
                confs,
                status,
                settleNow);
        return true;
    }

    private boolean alreadyCreditedAvailable(UUID transactionId) {
        if (transactionId == null) {
            return false;
        }
        return movementRepository.existsByTransactionIdAndMovementTypeIn(
                transactionId, KfeLedgerMovementTypes.USER_AVAILABLE_CREDIT_TYPES);
    }

    /** Movement-first credit; returns true when this caller owns the credit. */
    private boolean creditAvailableOnce(UUID transactionId, UUID walletId, long creditSats) {
        if (creditSats <= 0L) {
            return false;
        }
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

    private void notifyDeposit(KfeWalletEntity wallet, KfeTransactionEntity tx, long credited, int confs) {
        FinancialNotificationPort port = notificationPort.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            port.notifyDepositConfirmed(
                    wallet.getUserId(),
                    tx.getId(),
                    wallet.getId(),
                    "ONCHAIN",
                    credited,
                    confs);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Custodial Deposit] notify confirmed failed: {}",
                    exception.getMessage());
        }
    }

    private void notifyDetected(KfeWalletEntity wallet, KfeTransactionEntity tx, long amount, int confs) {
        FinancialNotificationPort port = notificationPort.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            port.notifyDepositDetected(
                    wallet.getUserId(),
                    tx.getId(),
                    wallet.getId(),
                    "ONCHAIN",
                    amount,
                    confs);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Custodial Deposit] notify detected failed: {}",
                    exception.getMessage());
        }
    }

    private void resyncObserved(UUID walletId) {
        KfeOnchainBalanceSyncService sync = onchainBalanceSyncService.getIfAvailable();
        if (sync == null) {
            return;
        }
        try {
            sync.syncWallet(walletId);
        } catch (RuntimeException exception) {
            log.debug(
                    "[KFE Custodial Deposit] observed resync failed walletId={}: {}",
                    walletId,
                    exception.getMessage());
        }
    }

    private static final class DepositAggregate {
        private String sampleAddress;
        private long amountSats;
        private int confirmations;

        private DepositAggregate(String sampleAddress, long amountSats, int confirmations) {
            this.sampleAddress = sampleAddress;
            this.amountSats = amountSats;
            this.confirmations = confirmations;
        }
    }
}
