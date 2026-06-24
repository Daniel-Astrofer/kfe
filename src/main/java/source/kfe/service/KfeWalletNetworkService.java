package source.kfe.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.dto.KfeColdWalletPsbtRequest;
import source.kfe.dto.KfeColdWalletPsbtResponse;
import source.kfe.dto.KfeReceivingCapabilitiesResponse;
import source.kfe.dto.KfeUtxoResponse;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletAddressStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.rail.BitcoinCoreRpcClient;
import source.kfe.rail.BlockchainClient;
import source.common.exception.FinancialProviderUnavailableException;
import source.common.financial.FinancialTransactionApprovalPort;
import source.common.financial.FinancialUserDirectoryPort;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

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

    public KfeWalletNetworkService(
            FinancialUserDirectoryPort userDirectory,
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            ObjectProvider<BlockchainClient> blockchainClientProvider,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClientProvider,
            KfeHashService hashService,
            KfeAuditLogService auditLogService,
            KfePsbtWorkflowService psbtWorkflowService,
            FinancialTransactionApprovalPort transactionApprovalPort) {
        this.userDirectory = userDirectory;
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.blockchainClientProvider = blockchainClientProvider;
        this.bitcoinCoreRpcClientProvider = bitcoinCoreRpcClientProvider;
        this.hashService = hashService;
        this.auditLogService = auditLogService;
        this.psbtWorkflowService = psbtWorkflowService;
        this.transactionApprovalPort = transactionApprovalPort;
    }

    @Transactional(readOnly = true)
    public KfeReceivingCapabilitiesResponse receivingCapabilities(String receiverIdentifier) {
        Optional<FinancialUserDirectoryPort.FinancialUserHandle> receiver = resolveUser(receiverIdentifier);
        if (receiver.isEmpty() || !Boolean.TRUE.equals(receiver.get().active())) {
            return unavailable("RECEIVER_NOT_READY");
        }

        List<KfeWalletEntity> activeWallets = walletRepository.findByUserIdOrderByCreatedAtDesc(receiver.get().id())
                .stream()
                .filter(wallet -> wallet.getStatus() == KfeWalletStatus.ACTIVE)
                .toList();

        Optional<KfeWalletEntity> internalWallet = activeWallets.stream()
                .filter(wallet -> wallet.getKind() == KfeWalletKind.INTERNAL && wallet.isSpendable())
                .findFirst();
        boolean internal = internalWallet.isPresent();
        boolean onchain = activeWallets.stream().anyMatch(this::hasActiveReceiveAddress);
        boolean lightning = false;

        List<String> rails = availableRails(internal, lightning, onchain);
        List<String> missing = new ArrayList<>();
        if (!internal) {
            missing.add("KFE_INTERNAL_WALLET_NOT_FOUND");
        }
        if (!lightning) {
            missing.add("KFE_LIGHTNING_RECEIVE_NOT_CONFIGURED");
        }
        if (!onchain) {
            missing.add("KFE_ONCHAIN_ADDRESS_NOT_FOUND");
        }

        return new KfeReceivingCapabilitiesResponse(
                internal,
                lightning,
                onchain,
                rails.isEmpty() ? null : rails.get(0),
                List.copyOf(missing),
                "@" + receiver.get().username(),
                internalWallet.map(KfeWalletEntity::getId).orElse(null),
                rails,
                DEFAULT_LIMITS);
    }

    @Transactional(readOnly = true)
    public List<KfeUtxoResponse> listUtxos(Long userId, UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserId(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        requireActive(wallet);

        BlockchainClient blockchainClient = requireBlockchainClient();
        List<KfeUtxoResponse> responses = new ArrayList<>();
        for (KfeWalletAddressEntity address : activeAddresses(walletId)) {
            blockchainClient.getUnspentOutputs(address.getAddress())
                    .forEach(utxo -> responses.add(new KfeUtxoResponse(
                            utxo.txid(),
                            utxo.vout(),
                            utxo.valueSats(),
                            utxo.scriptPubKey(),
                            address.getAddress())));
        }
        return List.copyOf(responses);
    }

    @Transactional
    public KfeColdWalletPsbtResponse createColdWalletPsbt(
            Long userId,
            UUID walletId,
            KfeColdWalletPsbtRequest request) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserId(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        requireActive(wallet);
        if (wallet.getKind() != KfeWalletKind.WATCH_ONLY) {
            throw new IllegalArgumentException("Cold wallet PSBT creation requires a WATCH_ONLY wallet.");
        }
        transactionApprovalPort.approveColdWalletPsbt(userId, request.totpCode());

        BitcoinCoreRpcClient bitcoinCore = bitcoinCoreRpcClientProvider.getIfAvailable();
        if (bitcoinCore == null) {
            throw new FinancialProviderUnavailableException("Bitcoin Core RPC is unavailable for KFE PSBT creation.");
        }

        List<KfeColdWalletPsbtRequest.Input> inputs = normalizeInputs(request.inputs());
        if (inputs.isEmpty()) {
            inputs = listUtxos(userId, walletId).stream()
                    .map(utxo -> new KfeColdWalletPsbtRequest.Input(utxo.txid(), utxo.vout()))
                    .toList();
        }
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("No UTXOs are available for this cold wallet.");
        }

        BitcoinCoreRpcClient.FundedPsbt fundedPsbt = bitcoinCore.createWatchOnlyPsbt(
                inputs.stream()
                        .map(input -> new BitcoinCoreRpcClient.PsbtInput(input.txid(), input.vout()))
                        .toList(),
                request.destinationAddress().trim(),
                request.amountSats(),
                request.confirmationTarget(),
                request.feeRateSatsPerVbyte());
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
                        "inputCount", String.valueOf(inputs.size())));

        var workflow = psbtWorkflowService.create(
                userId,
                walletId,
                fundedPsbt.psbt(),
                psbtHash,
                fundedPsbt.feeSats(),
                request.amountSats(),
                request.destinationAddress().trim(),
                inputs);

        return new KfeColdWalletPsbtResponse(
                workflow.getId(),
                fundedPsbt.psbt(),
                psbtHash,
                fundedPsbt.feeSats(),
                request.amountSats(),
                request.destinationAddress().trim(),
                inputs);
    }

    private Optional<FinancialUserDirectoryPort.FinancialUserHandle> resolveUser(String receiverIdentifier) {
        if (!hasText(receiverIdentifier)) {
            return Optional.empty();
        }
        String normalized = receiverIdentifier.trim();
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        if (normalized.matches("\\d+")) {
            return userDirectory.findById(Long.parseLong(normalized));
        }
        return userDirectory.findByUsername(normalized);
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
                List.of(),
                DEFAULT_LIMITS);
    }

    private boolean hasActiveReceiveAddress(KfeWalletEntity wallet) {
        return addressRepository
                .findTopByWalletIdAndStatusOrderByCreatedAtDesc(wallet.getId(), KfeWalletAddressStatus.ACTIVE)
                .isPresent();
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
