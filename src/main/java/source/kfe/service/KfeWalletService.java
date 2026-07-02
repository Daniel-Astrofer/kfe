package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import source.common.service.AddressDerivationService;
import source.kfe.dto.KfeAddressResponse;
import source.kfe.dto.KfeCreateWalletRequest;
import source.kfe.dto.KfeUpdateWalletRequest;
import source.kfe.dto.KfeWalletNameOption;
import source.kfe.dto.KfeWalletResponse;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletAddressRole;
import source.kfe.model.KfeWalletAddressStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletName;
import source.kfe.model.KfeWalletStatus;
import source.common.exception.FinancialProviderUnavailableException;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

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
            TransactionTemplate transactionTemplate) {
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
    }

    public KfeWalletResponse createWallet(Long userId, KfeCreateWalletRequest request) {
        validateCreateRequest(request);

        PendingWallet pending = Objects.requireNonNull(transactionTemplate.execute(status ->
                createPendingWallet(userId, request)));
        String proposalHash = kfeWalletCreateProposalHash(userId, pending);
        KfeQuorumGateway.Result quorum = requireWalletCreateQuorum(userId, pending, proposalHash);
        String mpcPublicKey = provisionMpcPublicKey(userId, pending);

        try {
            return Objects.requireNonNull(transactionTemplate.execute(status ->
                    activateWallet(userId, request, pending.walletId(), proposalHash, quorum, mpcPublicKey)));
        } catch (RuntimeException exception) {
            markWalletCreationFailed(
                    userId,
                    pending.walletId(),
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
        } else if (Boolean.TRUE.equals(request.issueInitialAddress())) {
            issueFreshAddress(wallet, false);
        }

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
        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY) {
            throw new IllegalArgumentException("WATCH_ONLY wallets do not issue receiving addresses.");
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
            return saveAddress(
                    wallet,
                    derived.address(),
                    "m/84'/0'/0'/0/" + nextIndex,
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
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setWalletId(wallet.getId());
        address.setAddress(addressValue);
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
