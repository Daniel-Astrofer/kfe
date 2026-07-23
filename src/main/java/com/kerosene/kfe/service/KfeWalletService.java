package com.kerosene.kfe.service;

import java.time.ZoneOffset;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import source.common.service.AddressDerivationService;
import com.kerosene.kfe.dto.KfeAddressResponse;
import com.kerosene.kfe.dto.KfeCreateWalletRequest;
import com.kerosene.kfe.dto.KfeUpdateWalletRequest;
import com.kerosene.kfe.dto.KfeWalletNameOption;
import com.kerosene.kfe.dto.KfeWalletResponse;
import com.kerosene.kfe.model.KfeWalletAddressEntity;
import com.kerosene.kfe.model.KfeWalletAddressRole;
import com.kerosene.kfe.model.KfeWalletAddressStatus;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.model.KfeWalletName;
import com.kerosene.kfe.model.KfeWalletStatus;
import source.common.exception.FinancialProviderUnavailableException;
import com.kerosene.kfe.rail.BitcoinCoreRpcClient;
import com.kerosene.kfe.repository.KfeWalletAddressRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class KfeWalletService {

    private static final Logger log = LoggerFactory.getLogger(KfeWalletService.class);
    private static final String ASSET_BTC = "BTC";
    private static final String INTERNAL_GLOBAL_WALLET_LABEL = "Conta Assegurada";
    private static final int FAILURE_REASON_MAX_LENGTH = 180;
    private static final int MAX_ACTIVE_WATCH_ONLY_WALLETS = 2;
    private static final List<KfeWalletStatus> USER_VISIBLE_WALLET_STATUSES = List.of(
            KfeWalletStatus.CREATING,
            KfeWalletStatus.ACTIVE,
            KfeWalletStatus.FROZEN,
            KfeWalletStatus.ROTATING_ADDRESS);
    private static final List<KfeWalletStatus> UNIQUE_WALLET_STATUSES = USER_VISIBLE_WALLET_STATUSES;

    private final KfeWalletRepository walletRepository;
    private final KfeWalletAddressRepository addressRepository;
    private final KfeBalanceService balanceService;
    private final KfeHashService hashService;
    private final KfeAuditLogService auditLogService;
    private final KfeQuorumGateway quorumGateway;
    private final KfeMpcKeyService mpcKeyService;
    private final KfeResponseMapper responseMapper;
    private final KfeDashboardPublisher dashboardPublisher;
    private final AddressDerivationService addressDerivationService;
    private final KfeReceiveAddressIssuer receiveAddressIssuer;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService;
    private final ObjectProvider<KfeColdWalletObservationService> coldWalletObservationService;
    private final ObjectProvider<KfeMonitoredChainAddressIndex> monitoredChainAddressIndex;

    public KfeWalletService(
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            KfeBalanceService balanceService,
            KfeHashService hashService,
            KfeAuditLogService auditLogService,
            KfeQuorumGateway quorumGateway,
            KfeMpcKeyService mpcKeyService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            AddressDerivationService addressDerivationService,
            KfeReceiveAddressIssuer receiveAddressIssuer,
            TransactionTemplate transactionTemplate,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService,
            ObjectProvider<KfeColdWalletObservationService> coldWalletObservationService,
            ObjectProvider<KfeMonitoredChainAddressIndex> monitoredChainAddressIndex) {
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.balanceService = balanceService;
        this.hashService = hashService;
        this.auditLogService = auditLogService;
        this.quorumGateway = quorumGateway;
        this.mpcKeyService = mpcKeyService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.addressDerivationService = addressDerivationService;
        this.receiveAddressIssuer = receiveAddressIssuer;
        this.transactionTemplate = transactionTemplate;
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient;
        this.onchainBalanceSyncService = onchainBalanceSyncService;
        this.coldWalletObservationService = coldWalletObservationService;
        this.monitoredChainAddressIndex = monitoredChainAddressIndex;
    }

    public KfeWalletResponse createWallet(Long userId, KfeCreateWalletRequest request) {
        validateCreateRequest(request);

        PendingWallet pending = Objects.requireNonNull(transactionTemplate.execute(status ->
                createPendingWallet(userId, request)));
        String proposalHash = kfeWalletCreateProposalHash(userId, pending);
        KfeQuorumGateway.Result quorum = requireWalletCreateQuorum(userId, pending, proposalHash);
        String mpcPublicKey = provisionMpcPublicKey(userId, pending);

        final UUID walletId = pending.walletId();
        try {
            KfeWalletResponse response = Objects.requireNonNull(transactionTemplate.execute(status ->
                    activateWallet(userId, request, walletId, proposalHash, quorum, mpcPublicKey)));
            // Core RPC must run AFTER DB commit. Nested @Transactional failures
            // (scantxoutset / getdescriptorinfo) mark the outer TX rollback-only
            // even when exceptions are caught — surfacing as UnexpectedRollbackException.
            runPostActivationChainHooks(walletId);
            return response;
        } catch (RuntimeException exception) {
            markWalletCreationFailed(
                    userId,
                    walletId,
                    KfeWalletStatus.KEYGEN_FAILED,
                    "Wallet activation failed: " + safeReason(exception));
            throw exception;
        }
    }

    private PendingWallet createPendingWallet(Long userId, KfeCreateWalletRequest request) {
        requireWalletCapacity(userId, request.kind());
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setUserId(userId);
        wallet.setKind(request.kind());
        wallet.setStatus(KfeWalletStatus.CREATING);
        wallet.setLabel(resolveWalletLabel(request));
        wallet.setAsset(ASSET_BTC);
        wallet.setSpendable(request.kind() != KfeWalletKind.WATCH_ONLY);
        wallet.setXpub(blankToNull(request.xpub()));
        wallet.setDescriptor(blankToNull(request.descriptor()));
        wallet.setFingerprint(blankToNull(request.fingerprint()));
        wallet.setDerivationPath(blankToNull(request.derivationPath()));
        wallet.setQuorumPolicyHash(quorumPolicyHash(request.kind()));
        wallet = walletRepository.save(wallet);
        balanceService.createEmptyBalance(wallet.getId(), wallet.getAsset());
        dashboardPublisher.publishAfterCommit(userId);
        return new PendingWallet(wallet.getId(), wallet.getKind(), wallet.getQuorumPolicyHash());
    }

    private void requireWalletCapacity(Long userId, KfeWalletKind kind) {
        long activeWallets = walletRepository.countByUserIdAndKindAndStatusIn(userId, kind, UNIQUE_WALLET_STATUSES);
        if (kind == KfeWalletKind.WATCH_ONLY) {
            if (activeWallets >= MAX_ACTIVE_WATCH_ONLY_WALLETS) {
                throw new IllegalArgumentException("É permitido criar no máximo duas carteiras frias ativas.");
            }
            return;
        }
        if (activeWallets > 0) {
            throw new IllegalArgumentException("Já existe uma carteira ativa ou em criação para este método de custódia.");
        }
    }

    private KfeQuorumGateway.Result requireWalletCreateQuorum(
            Long userId,
            PendingWallet pending,
            String proposalHash) {
        try {
            return quorumGateway.requireHealthyUnanimousConsensus(proposalHash);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Wallet] Wallet creation quorum unavailable walletId={} kind={}: {}",
                    pending.walletId(),
                    pending.kind(),
                    exception.getMessage());
            markWalletCreationFailed(
                    userId,
                    pending.walletId(),
                    KfeWalletStatus.QUORUM_BLOCKED,
                    "Quorum failed: " + safeReason(exception));
            throw new FinancialProviderUnavailableException(
                    "KFE wallet quorum is temporarily unavailable. Wallet creation was not completed.",
                    exception);
        }
    }

    private String provisionMpcPublicKey(Long userId, PendingWallet pending) {
        if (pending.kind() != KfeWalletKind.CUSTODIAL_ONCHAIN) {
            return null;
        }

        try {
            String publicKey = mpcKeyService.keygenWallet(pending.walletId(), userId);
            if (!hasText(publicKey)) {
                throw new IllegalStateException("MPC sidecar returned an empty public key.");
            }
            return publicKey;
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Wallet] MPC key generation unavailable walletId={}: {}",
                    pending.walletId(),
                    exception.getMessage());
            markWalletCreationFailed(
                    userId,
                    pending.walletId(),
                    KfeWalletStatus.KEYGEN_FAILED,
                    "MPC key generation failed: " + safeReason(exception));
            throw new FinancialProviderUnavailableException(
                    "KFE MPC key generation is temporarily unavailable. Wallet creation was not completed.",
                    exception);
        }
    }

    private KfeWalletResponse activateWallet(
            Long userId,
            KfeCreateWalletRequest request,
            UUID walletId,
            String proposalHash,
            KfeQuorumGateway.Result quorum,
            String mpcPublicKey) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        wallet.setMpcPublicKey(mpcPublicKey);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet = walletRepository.save(wallet);

        if (hasText(request.initialAddress())) {
            createProvidedAddress(wallet, request);
        } else if (Boolean.TRUE.equals(request.issueInitialAddress())
                || shouldAutoIssueWatchOnlyAddress(wallet, request)) {
            issueFreshAddress(wallet, false);
        }

        // Bitcoin Core import + observed balance sync intentionally deferred to
        // runPostActivationChainHooks() after this transaction commits.

        auditLogService.record(
                "KFE_WALLET_CREATED",
                null,
                wallet.getId(),
                null,
                null,
                Map.of(
                        "walletId", wallet.getId().toString(),
                        "kind", wallet.getKind().name(),
                        "proposalHash", proposalHash,
                        "quorumAckCount", quorum.acceptedNodes()));
        dashboardPublisher.publishAfterCommit(userId);
        return responseMapper.toWalletResponse(wallet);
    }

    /**
     * Best-effort chain hooks after wallet row is ACTIVE and committed.
     * Failures never undo wallet creation.
     *
     * <p>For WATCH_ONLY, monitoring starts at registration: balance probe + first history
     * observation (UTXOs already present + later scheduled scans). Pre-registration chain
     * history is intentionally out of scope without a full indexer.
     */
    private void runPostActivationChainHooks(UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
        if (wallet == null) {
            return;
        }
        importWatchOnlyIntoBitcoinCore(wallet);
        syncChainObservedBestEffort(wallet);
        startColdHistoryMonitoringBestEffort(wallet);
    }

    private void syncChainObservedBestEffort(KfeWalletEntity wallet) {
        if (wallet.getKind() != KfeWalletKind.WATCH_ONLY
                && wallet.getKind() != KfeWalletKind.CUSTODIAL_ONCHAIN) {
            return;
        }
        KfeOnchainBalanceSyncService sync = onchainBalanceSyncService.getIfAvailable();
        if (sync == null) {
            return;
        }
        try {
            sync.syncWallet(wallet.getId());
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Wallet] Initial chain balance sync failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        } catch (Exception exception) {
            log.warn(
                    "[KFE Wallet] Initial chain balance sync failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
    }

    /**
     * Immediately indexes post-registration cold activity (current UTXOs as inbound
     * observations). Ongoing activity is picked up by {@link KfeColdWalletObservationService}
     * and PSBT broadcast hooks.
     */
    private void startColdHistoryMonitoringBestEffort(KfeWalletEntity wallet) {
        if (wallet.getKind() != KfeWalletKind.WATCH_ONLY) {
            return;
        }
        KfeColdWalletObservationService coldObs = coldWalletObservationService.getIfAvailable();
        if (coldObs == null) {
            return;
        }
        try {
            coldObs.observeWallet(wallet.getId());
            KfeMonitoredChainAddressIndex index = monitoredChainAddressIndex.getIfAvailable();
            if (index != null) {
                try {
                    index.rebuild();
                } catch (RuntimeException ignored) {
                    // ZMQ filter will refresh on its own schedule
                }
            }
            log.info(
                    "[KFE Wallet] Cold history monitoring started at registration walletId={}",
                    wallet.getId());
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Wallet] Initial cold history observation failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        } catch (Exception exception) {
            log.warn(
                    "[KFE Wallet] Initial cold history observation failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
    }

    /**
     * Cold/watch-only wallets should always get a first receive address when an xpub
     * is present, even if the client forgot {@code issueInitialAddress=true}.
     */
    private boolean shouldAutoIssueWatchOnlyAddress(KfeWalletEntity wallet, KfeCreateWalletRequest request) {
        return wallet.getKind() == KfeWalletKind.WATCH_ONLY
                && hasText(wallet.getXpub())
                && !hasText(request.initialAddress());
    }

    /**
     * Best-effort Core import so listunspent / payment monitors can see cold funds.
     * Failure is logged but does not roll back wallet creation (client can still store xpub).
     */
    private void importWatchOnlyIntoBitcoinCore(KfeWalletEntity wallet) {
        if (wallet.getKind() != KfeWalletKind.WATCH_ONLY) {
            return;
        }
        BitcoinCoreRpcClient core = bitcoinCoreRpcClient.getIfAvailable();
        if (core == null) {
            log.warn(
                    "[KFE Wallet] Bitcoin Core RPC unavailable; watch-only descriptor not imported walletId={}",
                    wallet.getId());
            return;
        }
        String descriptor = resolveWatchOnlyDescriptor(wallet);
        if (!hasText(descriptor)) {
            log.warn(
                    "[KFE Wallet] No descriptor/xpub to import for watch-only walletId={}",
                    wallet.getId());
            return;
        }
        try {
            // Best-effort: shared hot Core wallets with private keys often reject
            // watch-only descriptor imports (-4). Balance still comes from scantxoutset.
            core.importWatchOnlyDescriptor(
                    descriptor,
                    java.time.LocalDateTime.ofInstant(
                            java.time.Instant.EPOCH,
                            java.time.ZoneOffset.UTC));
            log.info(
                    "[KFE Wallet] Imported watch-only descriptor into Bitcoin Core walletId={}",
                    wallet.getId());
        } catch (RuntimeException exception) {
            String msg = exception.getMessage() == null ? "" : exception.getMessage();
            if (msg.contains("private keys") || msg.contains("watch-only")) {
                log.info(
                        "[KFE Wallet] Core wallet cannot hold watch-only descriptors walletId={} — using scantxoutset for balance",
                        wallet.getId());
            } else {
                log.warn(
                        "[KFE Wallet] Failed to import watch-only descriptor walletId={}: {}",
                        wallet.getId(),
                        msg);
            }
        } catch (Exception exception) {
            log.warn(
                    "[KFE Wallet] Failed to import watch-only descriptor walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
    }

    private String resolveWatchOnlyDescriptor(KfeWalletEntity wallet) {
        // Prefer rebuilding from xpub when the stored descriptor looks truncated/invalid.
        // Historical bug: StringCryptoConverter padded/truncated to 128 bytes and clipped
        // "/0/*)" off electrum descriptors (≈133+ chars), which Core then rejects.
        if (hasText(wallet.getDescriptor())) {
            String stored = rewriteDescriptorXpubsForNetwork(wallet.getDescriptor().trim());
            if (isUsableOutputDescriptor(stored)) {
                return stored;
            }
            log.warn(
                    "[KFE Wallet] Stored descriptor unusable for walletId={} (len={}); rebuilding from xpub",
                    wallet.getId(),
                    stored.length());
        }
        if (!hasText(wallet.getXpub())) {
            return null;
        }
        return buildWpkhReceiveDescriptor(wallet);
    }

    private String buildWpkhReceiveDescriptor(KfeWalletEntity wallet) {
        String fingerprint = hasText(wallet.getFingerprint())
                ? wallet.getFingerprint().trim()
                : "00000000";
        String accountPath = hasText(wallet.getDerivationPath())
                ? wallet.getDerivationPath().trim().replaceFirst("^m/", "").replace("'", "h")
                : "84h/0h/0h";
        // Electrum standard account path is bare "m" → omit path in origin if empty.
        String origin = accountPath.isEmpty() || "m".equalsIgnoreCase(accountPath)
                ? fingerprint
                : fingerprint + "/" + accountPath;
        String networkXpub = addressDerivationService.toNetworkExtendedPublicKey(wallet.getXpub().trim());
        return "wpkh([" + origin + "]" + networkXpub + "/0/*)";
    }

    /**
     * Minimal structural check before handing a descriptor to Bitcoin Core.
     * Truncated ciphertext decrypts to strings missing the closing {@code /*)} branch.
     */
    private static boolean isUsableOutputDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return false;
        }
        String bare = descriptor.trim();
        int hash = bare.indexOf('#');
        if (hash >= 0) {
            bare = bare.substring(0, hash);
        }
        if (!bare.contains("(") || !bare.endsWith(")")) {
            return false;
        }
        // Range descriptors we import always include a wildcard branch.
        if (bare.contains("/*") || bare.matches(".*\\)/\\d+\\)$")) {
            return true;
        }
        // addr(...) or raw single-key without wildcard is still usable.
        return bare.startsWith("addr(") || bare.startsWith("raw(");
    }

    private String rewriteDescriptorXpubsForNetwork(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return descriptor;
        }
        // Match xpub/tpub/ypub/zpub/upub/vpub base58 bodies and re-encode for this network.
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([xtyzuv]pub[1-9A-HJ-NP-Za-km-z]{20,})\\b");
        java.util.regex.Matcher matcher = pattern.matcher(descriptor);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group(1);
            String rewritten;
            try {
                rewritten = addressDerivationService.toNetworkExtendedPublicKey(raw);
            } catch (RuntimeException ignored) {
                rewritten = raw;
            }
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rewritten));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public List<KfeWalletResponse> listWallets(Long userId) {
        return walletRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, USER_VISIBLE_WALLET_STATUSES).stream()
                .map(responseMapper::toWalletResponse)
                .toList();
    }

    @Transactional
    public KfeWalletResponse updateWallet(Long userId, UUID walletId, KfeUpdateWalletRequest request) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        if (wallet.getStatus() == KfeWalletStatus.ARCHIVED) {
            throw new IllegalStateException("Archived wallets cannot be updated.");
        }
        if (request == null || !hasText(request.label())) {
            throw new IllegalArgumentException("Wallet label is required.");
        }
        wallet.setLabel(request.label().trim());
        wallet = walletRepository.save(wallet);
        auditLogService.record(
                "KFE_WALLET_UPDATED",
                null,
                wallet.getId(),
                null,
                null,
                Map.of("walletId", wallet.getId().toString(), "label", wallet.getLabel()));
        dashboardPublisher.publishAfterCommit(userId);
        return responseMapper.toWalletResponse(wallet);
    }

    @Transactional
    public KfeWalletResponse archiveWallet(Long userId, UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        if (wallet.getStatus() == KfeWalletStatus.ARCHIVED) {
            return responseMapper.toWalletResponse(wallet);
        }
        if (wallet.getStatus() == KfeWalletStatus.ROTATING_ADDRESS || wallet.getStatus() == KfeWalletStatus.CREATING) {
            throw new IllegalStateException("Wallet cannot be archived while it is being created or rotated.");
        }
        wallet.setStatus(KfeWalletStatus.ARCHIVED);
        wallet.setSpendable(false);
        wallet = walletRepository.save(wallet);
        auditLogService.record(
                "KFE_WALLET_ARCHIVED",
                null,
                wallet.getId(),
                null,
                null,
                Map.of("walletId", wallet.getId().toString()));
        dashboardPublisher.publishAfterCommit(userId);
        return responseMapper.toWalletResponse(wallet);
    }

    public List<KfeWalletNameOption> availableWalletNames() {
        return List.of(KfeWalletName.values()).stream()
                .map(name -> new KfeWalletNameOption(name, name.label()))
                .toList();
    }

    /**
     * Returns an active receive address for {@code walletId}, issuing one if needed.
     * Used when routing platform on-chain payments to a user's custodial/cold sink.
     */
    @Transactional
    public String ensureActiveReceiveAddress(Long userId, UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        requireActive(wallet);
        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY && !hasText(wallet.getXpub())
                && !receiveAddressIssuer.canIssue()) {
            throw new IllegalStateException(
                    "WATCH_ONLY wallet has no XPUB and cannot issue a receive address.");
        }
        List<KfeWalletAddressEntity> active = addressRepository.findByWalletIdAndStatusOrderByCreatedAtDesc(
                wallet.getId(), KfeWalletAddressStatus.ACTIVE);
        for (KfeWalletAddressEntity row : active) {
            if (row.getAddress() != null
                    && !row.getAddress().isBlank()
                    && (row.getAddressRole() == null
                            || row.getAddressRole() == KfeWalletAddressRole.RECEIVE
                            || row.getAddressRole() == KfeWalletAddressRole.MONITOR)) {
                return row.getAddress().trim();
            }
        }
        KfeWalletAddressEntity issued = issueFreshAddress(wallet, false);
        return issued.getAddress().trim();
    }

    public KfeAddressResponse rotateAddress(Long userId, UUID walletId) {
        PendingAddressRotation pending = Objects.requireNonNull(transactionTemplate.execute(status ->
                beginAddressRotation(userId, walletId)));
        KfeQuorumGateway.Result quorum;
        try {
            quorum = quorumGateway.requireHealthyUnanimousConsensus(pending.proposalHash());
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Wallet] Address rotation quorum unavailable walletId={}: {}",
                    walletId,
                    exception.getMessage());
            restoreWalletStatus(userId, walletId, KfeWalletStatus.ACTIVE, "Address rotation quorum failed.");
            throw new FinancialProviderUnavailableException(
                    "KFE wallet quorum is temporarily unavailable. Address rotation was not completed.",
                    exception);
        }

        try {
            return Objects.requireNonNull(transactionTemplate.execute(status ->
                    finishAddressRotation(userId, walletId, pending.proposalHash(), quorum)));
        } catch (RuntimeException exception) {
            restoreWalletStatus(userId, walletId, KfeWalletStatus.ACTIVE, "Address rotation failed.");
            throw exception;
        }
    }

    private PendingAddressRotation beginAddressRotation(Long userId, UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY && !hasText(wallet.getXpub())) {
            throw new IllegalArgumentException(
                    "WATCH_ONLY wallets require an XPUB to issue receiving addresses.");
        }
        requireActive(wallet);

        wallet.setStatus(KfeWalletStatus.ROTATING_ADDRESS);
        walletRepository.save(wallet);
        String proposalHash = hashService.sha256("KFE_WALLET_ADDRESS_ROTATE|" + userId + "|" + wallet.getId());
        dashboardPublisher.publishAfterCommit(userId);
        return new PendingAddressRotation(proposalHash);
    }

    private KfeAddressResponse finishAddressRotation(
            Long userId,
            UUID walletId,
            String proposalHash,
            KfeQuorumGateway.Result quorum) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        KfeWalletAddressEntity address = issueFreshAddress(wallet, true);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        walletRepository.save(wallet);

        auditLogService.record(
                "KFE_WALLET_ADDRESS_ROTATED",
                null,
                wallet.getId(),
                null,
                null,
                Map.of(
                        "walletId", wallet.getId().toString(),
                        "addressId", address.getId().toString(),
                        "proposalHash", proposalHash,
                        "quorumAckCount", quorum.acceptedNodes()));
        dashboardPublisher.publishAfterCommit(userId);
        return responseMapper.toAddressResponse(address);
    }

    private void validateCreateRequest(KfeCreateWalletRequest request) {
        if (request.kind() == KfeWalletKind.SYSTEM_FUNDS || request.kind() == KfeWalletKind.SYSTEM_PROFIT) {
            throw new IllegalArgumentException("System wallets are managed by KFE runtime bootstrap.");
        }
        if (request.kind() == KfeWalletKind.WATCH_ONLY
                && !hasText(request.xpub())
                && !hasText(request.descriptor())) {
            throw new IllegalArgumentException("WATCH_ONLY wallets require xpub or descriptor.");
        }
        if (Boolean.TRUE.equals(request.issueInitialAddress())
                && !hasText(request.initialAddress())
                && !hasText(request.xpub())
                && !receiveAddressIssuer.canIssue()) {
            throw new IllegalArgumentException(
                    "Issuing an initial address requires an XPUB, initial address or configured KFE receive issuer.");
        }
    }

    private KfeWalletAddressEntity issueFreshAddress(KfeWalletEntity wallet, boolean retireExisting) {
        if (retireExisting) {
            List<KfeWalletAddressEntity> activeAddresses = addressRepository.findByWalletIdAndStatusOrderByCreatedAtDesc(
                    wallet.getId(),
                    KfeWalletAddressStatus.ACTIVE);
            activeAddresses.forEach(KfeWalletAddressEntity::retire);
            if (!activeAddresses.isEmpty()) {
                addressRepository.saveAll(activeAddresses);
            }
        }

        if (hasText(wallet.getXpub())) {
            int nextIndex = wallet.getLastDerivedIndex() + 1;
            AddressDerivationService.DerivedAddress derived =
                    addressDerivationService.deriveAddressDetailsFromXpub(wallet.getXpub(), nextIndex);
            wallet.setLastDerivedIndex(nextIndex);
            walletRepository.save(wallet);
            String accountPath = hasText(wallet.getDerivationPath())
                    ? wallet.getDerivationPath().trim()
                    : "m/84'/0'/0'";
            if ("m".equals(accountPath) || "m/".equals(accountPath)) {
                accountPath = "m";
            }
            String childPath = "m".equals(accountPath)
                    ? "m/0/" + nextIndex
                    : accountPath + "/0/" + nextIndex;
            return saveAddress(
                    wallet,
                    derived.address(),
                    childPath,
                    nextIndex,
                    "KFE_XPUB_DERIVATION");
        }

        KfeReceiveAddressIssuer.IssuedAddress issued = receiveAddressIssuer.issue(
                "kfe-wallet-" + wallet.getId());
        if (issued.derivationIndex() >= 0) {
            wallet.setLastDerivedIndex(issued.derivationIndex());
            walletRepository.save(wallet);
        }
        return saveAddress(
                wallet,
                issued.address(),
                issued.derivationPath(),
                issued.derivationIndex() >= 0 ? issued.derivationIndex() : null,
                issued.providerReference());
    }

    private KfeWalletAddressEntity createProvidedAddress(KfeWalletEntity wallet, KfeCreateWalletRequest request) {
        return saveAddress(
                wallet,
                request.initialAddress().trim(),
                blankToNull(request.initialAddressDerivationPath()),
                request.initialAddressDerivationIndex(),
                firstText(request.initialAddressProviderReference(), "CLIENT_PROVIDED"));
    }

    private KfeWalletAddressEntity saveAddress(
            KfeWalletEntity wallet,
            String addressValue,
            String derivationPath,
            Integer derivationIndex,
            String providerReference) {
        String normalized = addressValue == null ? "" : addressValue.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Address is required.");
        }

        // Re-import of the same seed/xpub reuses the same first receive address.
        // Unique constraint on address must not fail create after archive/KEYGEN_FAILED.
        java.util.Optional<KfeWalletAddressEntity> existing =
                addressRepository.findFirstByAddressIgnoreCase(normalized);
        if (existing.isPresent()) {
            KfeWalletAddressEntity address = existing.get();
            if (!wallet.getId().equals(address.getWalletId())) {
                KfeWalletEntity previous = walletRepository.findById(address.getWalletId()).orElse(null);
                if (previous != null && previous.getStatus() == KfeWalletStatus.ACTIVE
                        && !previous.getId().equals(wallet.getId())) {
                    throw new IllegalArgumentException(
                            "Este endereço já está vinculado a outra carteira ativa. "
                                    + "Arquive a carteira antiga antes de reimportar a mesma seed.");
                }
                address.setWalletId(wallet.getId());
            }
            address.setAddressRole(KfeWalletAddressRole.RECEIVE);
            address.setStatus(KfeWalletAddressStatus.ACTIVE);
            address.setDerivationPath(derivationPath);
            address.setDerivationIndex(derivationIndex);
            if (hasText(providerReference)) {
                address.setProviderReference(providerReference);
            }
            address.setRetiredAt(null);
            return addressRepository.save(address);
        }

        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setWalletId(wallet.getId());
        address.setAddress(normalized);
        address.setAddressRole(KfeWalletAddressRole.RECEIVE);
        address.setStatus(KfeWalletAddressStatus.ACTIVE);
        address.setDerivationPath(derivationPath);
        address.setDerivationIndex(derivationIndex);
        address.setProviderReference(providerReference);
        return addressRepository.save(address);
    }

    private void requireActive(KfeWalletEntity wallet) {
        if (wallet.getStatus() != KfeWalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet is not active.");
        }
    }

    private String quorumPolicyHash(KfeWalletKind kind) {
        return hashService.sha256("KFE_WALLET_POLICY|kind=" + kind
                + "|quorum=healthy-unanimous-min-2|pricing=onchain-0.9pct");
    }

    private String kfeWalletCreateProposalHash(Long userId, PendingWallet wallet) {
        return hashService.sha256("KFE_WALLET_CREATE|" + userId + "|" + wallet.walletId()
                + "|" + wallet.kind() + "|" + wallet.quorumPolicyHash());
    }

    private void markWalletCreationFailed(Long userId, UUID walletId, KfeWalletStatus status, String reason) {
        try {
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                walletRepository.findByIdAndUserIdForUpdate(walletId, userId).ifPresent(wallet -> {
                    wallet.setStatus(status);
                    walletRepository.save(wallet);
                    auditLogService.record(
                            "KFE_WALLET_CREATE_FAILED",
                            null,
                            wallet.getId(),
                            null,
                            null,
                            Map.of(
                                    "walletId", wallet.getId().toString(),
                                    "status", status.name(),
                                    "reason", safeReason(reason)));
                    dashboardPublisher.publishAfterCommit(userId);
                });
            });
        } catch (RuntimeException markerException) {
            log.warn(
                    "[KFE Wallet] Failed to persist wallet creation failure walletId={}: {}",
                    walletId,
                    markerException.getMessage());
        }
    }

    private void restoreWalletStatus(Long userId, UUID walletId, KfeWalletStatus status, String reason) {
        try {
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                walletRepository.findByIdAndUserIdForUpdate(walletId, userId).ifPresent(wallet -> {
                    wallet.setStatus(status);
                    walletRepository.save(wallet);
                    auditLogService.record(
                            "KFE_WALLET_STATUS_RESTORED",
                            null,
                            wallet.getId(),
                            null,
                            null,
                            Map.of(
                                    "walletId", wallet.getId().toString(),
                                    "status", status.name(),
                                    "reason", safeReason(reason)));
                    dashboardPublisher.publishAfterCommit(userId);
                });
            });
        } catch (RuntimeException markerException) {
            log.warn(
                    "[KFE Wallet] Failed to restore wallet status walletId={}: {}",
                    walletId,
                    markerException.getMessage());
        }
    }

    private String resolveWalletLabel(KfeCreateWalletRequest request) {
        if (request.kind() == KfeWalletKind.INTERNAL) {
            return INTERNAL_GLOBAL_WALLET_LABEL;
        }
        if (request.kind() == KfeWalletKind.SYSTEM_FUNDS) {
            return "Kerosene Fundos Globais";
        }
        if (request.kind() == KfeWalletKind.SYSTEM_PROFIT) {
            return "Kerosene Lucro";
        }
        if (hasText(request.label())) {
            return request.label().trim();
        }
        if (request.name() != null) {
            return request.name().label();
        }
        throw new IllegalArgumentException("Wallet label is required.");
    }

    private String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private String safeReason(RuntimeException exception) {
        return safeReason(exception.getMessage());
    }

    private String safeReason(String reason) {
        String clean = hasText(reason) ? reason.trim() : "unavailable";
        return clean.length() > FAILURE_REASON_MAX_LENGTH
                ? clean.substring(0, FAILURE_REASON_MAX_LENGTH)
                : clean;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PendingWallet(UUID walletId, KfeWalletKind kind, String quorumPolicyHash) {
    }

    private record PendingAddressRotation(String proposalHash) {
    }
}
