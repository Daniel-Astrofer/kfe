package com.kerosene.kfe.wallet.adapters.in.compatibility;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.wallet.adapters.out.bitcoin.BitcoinAddressValidator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeColdWalletPsbtRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeColdWalletPsbtResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeReceivingCapabilitiesResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeUtxoResponse;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressRole;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningInvoiceGateway;
import java.time.Instant;
import com.kerosene.common.exception.FinancialProviderUnavailableException;
import com.kerosene.common.financial.approval.DeviceProof;
import com.kerosene.common.financial.approval.FinancialTransactionApprovalPort;
import com.kerosene.common.financial.operations.FinancialUserDirectoryPort;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import com.kerosene.kfe.wallet.domain.model.ColdPsbtRequest;
import com.kerosene.kfe.wallet.domain.model.Outpoint;
import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;
import com.kerosene.kfe.wallet.domain.service.WalletPolicy;
import com.kerosene.kfe.wallet.domain.service.UtxoProjectionPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class KfeWalletNetworkService {

    private static final KfeReceivingCapabilitiesResponse.Limits DEFAULT_LIMITS =
            new KfeReceivingCapabilitiesResponse.Limits(
                    "BTC",
                    List.of("BRL"),
                    1L,
                    1L,
                    546L);

    private final FinancialUserDirectoryPort userDirectory;
    private final KfeWalletRepository walletRepository;
    private final KfeWalletAddressRepository addressRepository;
    private final ObjectProvider<BlockchainClient> blockchainClientProvider;
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClientProvider;
    private final KfeHashService hashService;
    private final KfeAuditLogService auditLogService;
    private final KfePsbtWorkflowService psbtWorkflowService;
    private final FinancialTransactionApprovalPort transactionApprovalPort;
    private final AddressDerivationService addressDerivationService;
    private final BitcoinAddressValidator bitcoinAddressValidator;
    private final LightningInvoiceGateway lightningInvoiceGateway;
    /** Shared with cold observe / onchain sync so listUtxos sees the same gap. */
    private final int descriptorScanRange;

    public KfeWalletNetworkService(
            FinancialUserDirectoryPort userDirectory,
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            ObjectProvider<BlockchainClient> blockchainClientProvider,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClientProvider,
            KfeHashService hashService,
            KfeAuditLogService auditLogService,
            KfePsbtWorkflowService psbtWorkflowService,
            FinancialTransactionApprovalPort transactionApprovalPort,
            AddressDerivationService addressDerivationService,
            BitcoinAddressValidator bitcoinAddressValidator,
            @Qualifier("kfeExternalLightningInvoiceGateway")
            LightningInvoiceGateway lightningInvoiceGateway,
            @Value("${kfe.descriptor-scan-range:200}") int descriptorScanRange) {
        this.userDirectory = userDirectory;
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.blockchainClientProvider = blockchainClientProvider;
        this.bitcoinCoreRpcClientProvider = bitcoinCoreRpcClientProvider;
        this.hashService = hashService;
        this.auditLogService = auditLogService;
        this.psbtWorkflowService = psbtWorkflowService;
        this.transactionApprovalPort = transactionApprovalPort;
        this.addressDerivationService = addressDerivationService;
        this.bitcoinAddressValidator = bitcoinAddressValidator;
        this.lightningInvoiceGateway = lightningInvoiceGateway;
        this.descriptorScanRange = Math.max(1, descriptorScanRange);
    }

    @Transactional(readOnly = true)
    public KfeReceivingCapabilitiesResponse receivingCapabilities(String receiverIdentifier) {
        return receivingCapabilities(null, receiverIdentifier);
    }

    @Transactional(readOnly = true)
    public KfeReceivingCapabilitiesResponse receivingCapabilities(
            Long senderUserId,
            String receiverIdentifier) {
        ResolvedReceiver resolved = resolveReceiver(receiverIdentifier);
        if (resolved == null
                || resolved.user() == null
                || !Boolean.TRUE.equals(resolved.user().active())) {
            return unavailable("RECEIVER_NOT_READY");
        }

        FinancialUserDirectoryPort.FinancialUserHandle receiver = resolved.user();
        List<KfeWalletEntity> activeWallets = walletRepository.findByUserIdOrderByCreatedAtDesc(receiver.id())
                .stream()
                .filter(wallet -> wallet.getStatus() == KfeWalletStatus.ACTIVE)
                .toList();

        // Prefer the wallet explicitly addressed by UUID (frontend locks destination to internalWalletId).
        Optional<KfeWalletEntity> internalWallet = Optional.empty();
        if (resolved.preferredWalletId() != null) {
            internalWallet = activeWallets.stream()
                    .filter(wallet -> resolved.preferredWalletId().equals(wallet.getId()))
                    .filter(wallet -> wallet.getKind() == KfeWalletKind.INTERNAL && wallet.isSpendable())
                    .findFirst();
        }
        if (internalWallet.isEmpty()) {
            internalWallet = activeWallets.stream()
                    .filter(wallet -> wallet.getKind() == KfeWalletKind.INTERNAL && wallet.isSpendable())
                    .findFirst();
        }
        boolean internal = internalWallet.isPresent();
        boolean lightning = internal && lightningInvoiceGateway.isLive();

        Optional<OnchainReceiveTarget> onchainTarget = resolveOnchainReceiveTarget(
                activeWallets,
                resolved.preferredWalletId());
        boolean onchain = onchainTarget.isPresent();

        List<String> rails = availableRails(internal, lightning, onchain);
        // preferredRail must only point at a rail that is actually available.
        String preferredRail = preferredRail(rails);

        List<String> missing = new ArrayList<>();
        if (!internal) {
            missing.add("KFE_INTERNAL_WALLET_NOT_FOUND");
        }
        if (!lightning) {
            if (!internal) {
                missing.add("KFE_LIGHTNING_RECEIVE_NOT_CONFIGURED");
            } else if (!lightningInvoiceGateway.isLive()) {
                missing.add("KFE_LIGHTNING_RECEIVE_NOT_CONFIGURED");
            }
        }
        if (!onchain) {
            missing.add("KFE_ONCHAIN_ADDRESS_NOT_FOUND");
        }

        List<KfeReceivingCapabilitiesResponse.SenderSourceWallet> eligibleSources =
                resolveEligibleSourceWallets(senderUserId, rails);

        return new KfeReceivingCapabilitiesResponse(
                internal,
                lightning,
                onchain,
                preferredRail,
                List.copyOf(missing),
                "@" + receiver.username(),
                internalWallet.map(KfeWalletEntity::getId).orElse(null),
                onchainTarget.map(OnchainReceiveTarget::address).orElse(null),
                onchainTarget.map(OnchainReceiveTarget::walletId).orElse(null),
                rails,
                eligibleSources,
                DEFAULT_LIMITS);
    }

    /**
     * Sender wallets that can fund at least one rail the receiver accepts.
     * Product rule: source list is delegated by the backend, not filtered locally.
     */
    private List<KfeReceivingCapabilitiesResponse.SenderSourceWallet> resolveEligibleSourceWallets(
            Long senderUserId,
            List<String> availableRails) {
        if (senderUserId == null || availableRails == null || availableRails.isEmpty()) {
            return List.of();
        }
        List<KfeWalletEntity> senderWallets = walletRepository
                .findByUserIdOrderByCreatedAtDesc(senderUserId)
                .stream()
                .filter(wallet -> wallet.getStatus() == KfeWalletStatus.ACTIVE)
                .toList();
        List<KfeReceivingCapabilitiesResponse.SenderSourceWallet> eligible = new ArrayList<>();
        for (KfeWalletEntity wallet : senderWallets) {
            List<String> compatible = availableRails.stream()
                    .filter(rail -> senderCanFundRail(wallet, rail))
                    .toList();
            if (compatible.isEmpty()) {
                continue;
            }
            eligible.add(new KfeReceivingCapabilitiesResponse.SenderSourceWallet(
                    wallet.getId(),
                    wallet.getKind().name(),
                    wallet.getLabel(),
                    List.copyOf(compatible)));
        }
        return List.copyOf(eligible);
    }

    private static boolean senderCanFundRail(KfeWalletEntity wallet, String rail) {
        if (wallet == null || rail == null) {
            return false;
        }
        String normalized = rail.trim().toUpperCase();
        KfeWalletKind kind = wallet.getKind();
        boolean spendable = wallet.isSpendable();
        return switch (normalized) {
            case "INTERNAL", "PAYMENT_LINK" -> kind == KfeWalletKind.INTERNAL && spendable;
            case "LIGHTNING" -> kind == KfeWalletKind.INTERNAL && spendable;
            case "ONCHAIN" -> (kind == KfeWalletKind.INTERNAL && spendable)
                    || (kind == KfeWalletKind.CUSTODIAL_ONCHAIN && spendable)
                    || kind == KfeWalletKind.WATCH_ONLY;
            default -> false;
        };
    }

    @Transactional(readOnly = true)
    public List<KfeUtxoResponse> listUtxos(Long userId, UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserId(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        requireActive(wallet);

        BlockchainClient blockchainClient = requireBlockchainClient();
        Map<Outpoint, UtxoSnapshot> projection = UtxoProjectionPolicy.newProjection();
        for (KfeWalletAddressEntity address : activeAddresses(walletId)) {
            // Merge listunspent + scantxoutset so cold/watch-only works without Core import.
            for (BlockchainClient.AddressUtxo utxo :
                    blockchainClient.getUnspentOutputsMerged(address.getAddress())) {
                putUtxo(projection, utxo, address.getAddress());
            }
        }
        // Descriptor gap coverage for cold wallets (same range as cold observe / onchain sync).
        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY && hasText(wallet.getDescriptor())) {
            try {
                for (BlockchainClient.AddressUtxo utxo :
                        blockchainClient.getUnspentOutputsFromScan(
                                wallet.getDescriptor().trim(), descriptorScanRange)) {
                    putUtxo(projection, utxo, utxo.address());
                }
            } catch (RuntimeException ignored) {
                // Address list is enough for a partial view.
            }
        }
        return projection.values().stream()
                .map(utxo -> new KfeUtxoResponse(
                        utxo.outpoint().txid(),
                        utxo.outpoint().vout(),
                        utxo.valueSats(),
                        utxo.scriptPubKey(),
                        utxo.address(),
                        utxo.confirmations()))
                .toList();
    }

    private static void putUtxo(
            Map<Outpoint, UtxoSnapshot> projection,
            BlockchainClient.AddressUtxo utxo,
            String fallbackAddress) {
        if (utxo == null || utxo.txid() == null || utxo.txid().isBlank()) {
            return;
        }
        String address = utxo.address() != null && !utxo.address().isBlank()
                ? utxo.address()
                : (fallbackAddress != null ? fallbackAddress : "");
        UtxoSnapshot candidate = new UtxoSnapshot(
                new Outpoint(utxo.txid(), utxo.vout()),
                utxo.valueSats(),
                utxo.scriptPubKey(),
                address,
                Math.max(0, utxo.confirmations()));
        UtxoProjectionPolicy.merge(projection, candidate);
    }

    @Transactional
    public KfeColdWalletPsbtResponse createColdWalletPsbt(
            Long userId,
            UUID walletId,
            KfeColdWalletPsbtRequest request) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserId(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        requireActive(wallet);
        WalletSnapshot custodyWallet = walletSnapshot(wallet);
        WalletPolicy.requireOwnedActive(custodyWallet, userId, walletId);
        WalletPolicy.requireBitcoinWallet(custodyWallet);
        WalletPolicy.requireColdPsbtWallet(custodyWallet);
        if (request.totpCode() != null && !request.totpCode().isBlank()) {
            transactionApprovalPort.approveColdWalletPsbt(
                    userId,
                    new DeviceProof("totp", request.totpCode(), "kfe-cold-wallet", Instant.now()));
        }

        String destination = WalletPolicy.requireDestination(new ColdPsbtRequest(
                request.destinationAddress(),
                request.amountSats(),
                request.confirmationTarget(),
                request.feeRateSatsPerVbyte(),
                List.of()));
        if (!bitcoinAddressValidator.isValidBitcoinAddressForConfiguredNetwork(destination)) {
            throw new IllegalArgumentException(
                    "destinationAddress is not valid for the configured Bitcoin network.");
        }

        BitcoinCoreRpcClient bitcoinCore = bitcoinCoreRpcClientProvider.getIfAvailable();
        if (bitcoinCore == null) {
            throw new FinancialProviderUnavailableException("Bitcoin Core RPC is unavailable for KFE PSBT creation.");
        }

        // Live UTXO set is the ownership source of truth for spendable inputs.
        List<KfeUtxoResponse> liveUtxos = listUtxos(userId, walletId);
        List<KfeColdWalletPsbtRequest.Input> inputs = normalizeInputs(request.inputs());
        List<UtxoSnapshot> liveSnapshots = liveUtxos.stream()
                .map(utxo -> new UtxoSnapshot(
                        new Outpoint(utxo.txid(), utxo.vout()),
                        utxo.valueSats(),
                        utxo.scriptPubKey(),
                        utxo.address(),
                        utxo.confirmations()))
                .toList();
        List<Outpoint> requestedOutpoints = inputs.stream()
                .map(input -> new Outpoint(input.txid(), input.vout()))
                .toList();
        inputs = WalletPolicy.selectOwnedInputs(liveSnapshots, requestedOutpoints).stream()
                .map(utxo -> new KfeColdWalletPsbtRequest.Input(
                        utxo.outpoint().txid(), utxo.outpoint().vout()))
                .toList();

        String changeAddress = resolveColdChangeAddress(wallet);
        BitcoinCoreRpcClient.FundedPsbt fundedPsbt = bitcoinCore.createWatchOnlyPsbt(
                inputs.stream()
                        .map(input -> new BitcoinCoreRpcClient.PsbtInput(input.txid(), input.vout()))
                        .toList(),
                destination,
                request.amountSats(),
                request.confirmationTarget(),
                request.feeRateSatsPerVbyte(),
                changeAddress);
        String psbtHash = hashService.sha256(fundedPsbt.psbt());

        auditLogService.record(
                "KFE_COLD_WALLET_PSBT_CREATED",
                null,
                walletId,
                null,
                null,
                Map.of(
                        "walletId", walletId.toString(),
                        "psbtHash", psbtHash,
                        "amountSats", String.valueOf(request.amountSats()),
                        "feeSats", String.valueOf(fundedPsbt.feeSats()),
                        "inputCount", String.valueOf(inputs.size()),
                        "changeAddressPresent", String.valueOf(hasText(changeAddress))));

        var workflow = psbtWorkflowService.create(
                userId,
                walletId,
                fundedPsbt.psbt(),
                psbtHash,
                fundedPsbt.feeSats(),
                request.amountSats(),
                destination,
                inputs);

        return new KfeColdWalletPsbtResponse(
                workflow.getId(),
                fundedPsbt.psbt(),
                psbtHash,
                fundedPsbt.feeSats(),
                request.amountSats(),
                destination,
                inputs);
    }

    /**
     * Next unused change-branch address for cold PSBT change. Prefer existing CHANGE rows,
     * otherwise derive from xpub (index 0..N) and persist as CHANGE/ACTIVE.
     */
    private String resolveColdChangeAddress(KfeWalletEntity wallet) {
        if (wallet == null || !hasText(wallet.getXpub())) {
            throw new IllegalStateException("Cold wallet is missing xpub for change derivation.");
        }
        List<KfeWalletAddressEntity> existing =
                addressRepository.findByWalletIdAndStatusOrderByCreatedAtDesc(
                        wallet.getId(), KfeWalletAddressStatus.ACTIVE);
        int maxChangeIndex = -1;
        for (KfeWalletAddressEntity row : existing) {
            if (row.getAddressRole() == KfeWalletAddressRole.CHANGE) {
                // Prefer newest unused change address if present.
                if (hasText(row.getAddress())) {
                    return row.getAddress().trim();
                }
            }
        }
        // Derive a fresh change address at next free index (start at 0).
        int nextIndex = Math.max(0, maxChangeIndex + 1);
        String xpub = wallet.getXpub().trim();
        String address = addressDerivationService.deriveAddressFromXpub(xpub, nextIndex, true);
        if (!hasText(address)) {
            throw new IllegalStateException("Failed to derive cold change address.");
        }
        try {
            KfeWalletAddressEntity row = new KfeWalletAddressEntity();
            row.setWalletId(wallet.getId());
            row.setAddress(address.trim());
            row.setAddressRole(KfeWalletAddressRole.CHANGE);
            row.setStatus(KfeWalletAddressStatus.ACTIVE);
            addressRepository.save(row);
        } catch (RuntimeException ignored) {
            // unique race — address still usable
        }
        return address.trim();
    }

    private static WalletSnapshot walletSnapshot(KfeWalletEntity wallet) {
        return new WalletSnapshot(
                wallet.getId(),
                wallet.getUserId(),
                WalletKind.valueOf(wallet.getKind().name()),
                WalletStatus.valueOf(wallet.getStatus().name()),
                wallet.getAsset(),
                wallet.isSpendable(),
                wallet.getXpub() != null && !wallet.getXpub().isBlank(),
                wallet.getDescriptor() != null && !wallet.getDescriptor().isBlank(),
                wallet.getXpub(),
                wallet.getDescriptor());
    }

    /**
     * Resolves a receiver from username, numeric user id, or KFE wallet UUID.
     *
     * <p>The Flutter send flow calls this with a username first, then locks the destination to the
     * returned {@code internalWalletId}. A second capabilities check with that UUID must still work —
     * treating a wallet id as a username yields 404 from the core directory and false
     * {@code RECEIVER_NOT_READY}.</p>
     */
    private ResolvedReceiver resolveReceiver(String receiverIdentifier) {
        if (!hasText(receiverIdentifier)) {
            return null;
        }
        String normalized = receiverIdentifier.trim();
        while (normalized.startsWith("@")) {
            normalized = normalized.substring(1).trim();
        }
        if (!hasText(normalized)) {
            return null;
        }

        UUID walletId = parseUuid(normalized);
        if (walletId != null) {
            Optional<KfeWalletEntity> wallet = walletRepository.findById(walletId);
            if (wallet.isEmpty()) {
                return null;
            }
            Optional<FinancialUserDirectoryPort.FinancialUserHandle> user =
                    userDirectory.findById(wallet.get().getUserId());
            return user.map(handle -> new ResolvedReceiver(handle, walletId)).orElse(null);
        }

        Optional<FinancialUserDirectoryPort.FinancialUserHandle> user;
        if (normalized.matches("\\d+")) {
            user = userDirectory.findById(Long.parseLong(normalized));
        } else {
            user = userDirectory.findByUsername(normalized);
        }
        return user.map(handle -> new ResolvedReceiver(handle, null)).orElse(null);
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record ResolvedReceiver(
            FinancialUserDirectoryPort.FinancialUserHandle user,
            UUID preferredWalletId) {
    }

    private KfeReceivingCapabilitiesResponse unavailable(String reason) {
        return new KfeReceivingCapabilitiesResponse(
                false,
                false,
                false,
                null,
                List.of(reason),
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                DEFAULT_LIMITS);
    }

    private Optional<String> activeReceiveAddress(UUID walletId) {
        return addressRepository
                .findTopByWalletIdAndStatusOrderByCreatedAtDesc(walletId, KfeWalletAddressStatus.ACTIVE)
                .map(KfeWalletAddressEntity::getAddress)
                .filter(this::hasText)
                .map(String::trim);
    }

    /**
     * Picks the best public on-chain receive target for dual-rail send.
     * Order: preferred wallet → CUSTODIAL_ONCHAIN → INTERNAL → WATCH_ONLY (any with ACTIVE address).
     */
    private Optional<OnchainReceiveTarget> resolveOnchainReceiveTarget(
            List<KfeWalletEntity> activeWallets,
            UUID preferredWalletId) {
        if (preferredWalletId != null) {
            Optional<OnchainReceiveTarget> preferred = activeWallets.stream()
                    .filter(wallet -> preferredWalletId.equals(wallet.getId()))
                    .map(this::toOnchainTarget)
                    .flatMap(Optional::stream)
                    .findFirst();
            if (preferred.isPresent()) {
                return preferred;
            }
        }
        Optional<OnchainReceiveTarget> custodial = firstOnchainTarget(
                activeWallets, KfeWalletKind.CUSTODIAL_ONCHAIN);
        if (custodial.isPresent()) {
            return custodial;
        }
        Optional<OnchainReceiveTarget> internal = firstOnchainTarget(
                activeWallets, KfeWalletKind.INTERNAL);
        if (internal.isPresent()) {
            return internal;
        }
        return firstOnchainTarget(activeWallets, KfeWalletKind.WATCH_ONLY);
    }

    private Optional<OnchainReceiveTarget> firstOnchainTarget(
            List<KfeWalletEntity> wallets,
            KfeWalletKind kind) {
        return wallets.stream()
                .filter(wallet -> wallet.getKind() == kind)
                .map(this::toOnchainTarget)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<OnchainReceiveTarget> toOnchainTarget(KfeWalletEntity wallet) {
        return activeReceiveAddress(wallet.getId())
                .map(address -> new OnchainReceiveTarget(wallet.getId(), address));
    }

    private List<String> availableRails(boolean internal, boolean lightning, boolean onchain) {
        List<String> rails = new ArrayList<>();
        if (internal) {
            rails.add("INTERNAL");
        }
        if (lightning) {
            rails.add("LIGHTNING");
        }
        if (onchain) {
            rails.add("ONCHAIN");
        }
        return List.copyOf(rails);
    }

    /** Prefer INTERNAL, then LIGHTNING, then ONCHAIN — only among available rails. */
    private String preferredRail(List<String> rails) {
        if (rails == null || rails.isEmpty()) {
            return null;
        }
        if (rails.contains("INTERNAL")) {
            return "INTERNAL";
        }
        if (rails.contains("LIGHTNING")) {
            return "LIGHTNING";
        }
        if (rails.contains("ONCHAIN")) {
            return "ONCHAIN";
        }
        return rails.get(0);
    }

    private record OnchainReceiveTarget(UUID walletId, String address) {
    }

    private List<KfeWalletAddressEntity> activeAddresses(UUID walletId) {
        return addressRepository.findByWalletIdAndStatusOrderByCreatedAtDesc(
                walletId,
                KfeWalletAddressStatus.ACTIVE);
    }

    private List<KfeColdWalletPsbtRequest.Input> normalizeInputs(List<KfeColdWalletPsbtRequest.Input> inputs) {
        if (inputs == null) {
            return List.of();
        }
        return inputs.stream()
                .filter(input -> input != null && hasText(input.txid()))
                .map(input -> new KfeColdWalletPsbtRequest.Input(input.txid().trim(), input.vout()))
                .distinct()
                .toList();
    }

    private BlockchainClient requireBlockchainClient() {
        BlockchainClient blockchainClient = blockchainClientProvider.getIfAvailable();
        if (blockchainClient == null) {
            throw new FinancialProviderUnavailableException("Blockchain client is unavailable for KFE network data.");
        }
        return blockchainClient;
    }

    private void requireActive(KfeWalletEntity wallet) {
        if (wallet.getStatus() != KfeWalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet is not active.");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
