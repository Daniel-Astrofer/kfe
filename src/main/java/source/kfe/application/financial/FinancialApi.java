package source.kfe.application.financial;

import org.springframework.stereotype.Service;
import source.kfe.dto.KfeAddressResponse;
import source.kfe.dto.KfeColdWalletPsbtRequest;
import source.kfe.dto.KfeColdWalletPsbtResponse;
import source.kfe.dto.KfeCreateWalletRequest;
import source.kfe.dto.KfeReceivingCapabilitiesResponse;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.dto.KfeTransactionResponse;
import source.kfe.dto.KfeTransactionQuoteRequest;
import source.kfe.dto.KfeTransactionQuoteResponse;
import source.kfe.dto.KfeUpdateWalletRequest;
import source.kfe.dto.KfeUtxoResponse;
import source.kfe.dto.KfeWalletNameOption;
import source.kfe.dto.KfeWalletResponse;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.service.KfeResponseMapper;
import source.kfe.service.KfePricingService;
import source.kfe.service.KfeTransactionEngine;
import source.kfe.service.KfeWalletNetworkService;
import source.kfe.service.KfeWalletService;

import java.util.List;
import java.util.UUID;

@Service
public class FinancialApi {

    private final KfeTransactionEngine transactionEngine;
    private final KfeTransactionRepository transactionRepository;
    private final KfeResponseMapper responseMapper;
    private final KfePricingService pricingService;
    private final KfeWalletService walletService;
    private final KfeWalletNetworkService walletNetworkService;

    public FinancialApi(
            KfeTransactionEngine transactionEngine,
            KfeTransactionRepository transactionRepository,
            KfeResponseMapper responseMapper,
            KfePricingService pricingService,
            KfeWalletService walletService,
            KfeWalletNetworkService walletNetworkService) {
        this.transactionEngine = transactionEngine;
        this.transactionRepository = transactionRepository;
        this.responseMapper = responseMapper;
        this.pricingService = pricingService;
        this.walletService = walletService;
        this.walletNetworkService = walletNetworkService;
    }

    public KfeTransactionResponse submitTransaction(Long userId, KfeSubmitTransactionRequest request) {
        return submitTransaction(userId, request, null);
    }

    public KfeTransactionResponse submitTransaction(Long userId, KfeSubmitTransactionRequest request, String deviceHash) {
        return transactionEngine.submit(userId, request, deviceHash);
    }

    public KfeTransactionResponse existingTransactionByIdempotency(
            Long userId,
            String idempotencyKey,
            String requestHash) {
        return transactionEngine.getExistingByIdempotency(userId, idempotencyKey, requestHash);
    }

    public String transactionRequestHash(Long userId, KfeSubmitTransactionRequest request) {
        return transactionEngine.requestHash(userId, request);
    }

    public KfeTransactionQuoteResponse quoteTransaction(KfeTransactionQuoteRequest request) {
        KfePricingService.Quote quote = pricingService.quote(
                request.rail(),
                request.direction(),
                request.amountSats(),
                request.networkFeeSats());
        return new KfeTransactionQuoteResponse(
                request.rail(),
                request.direction(),
                quote.grossAmountSats(),
                quote.receiverAmountSats(),
                quote.networkFeeSats(),
                quote.totalDebitSats(),
                quote.keroseneFeeSats());
    }

    public KfeTransactionResponse transaction(Long userId, UUID transactionId) {
        return transactionRepository
                .findByIdAndUserId(transactionId, userId)
                .map(responseMapper::toTransactionResponse)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
    }

    public KfeWalletResponse createWallet(Long userId, KfeCreateWalletRequest request) {
        return walletService.createWallet(userId, request);
    }

    public List<KfeWalletResponse> wallets(Long userId) {
        return walletService.listWallets(userId);
    }

    public KfeWalletResponse updateWallet(Long userId, UUID walletId, KfeUpdateWalletRequest request) {
        return walletService.updateWallet(userId, walletId, request);
    }

    public KfeWalletResponse archiveWallet(Long userId, UUID walletId) {
        return walletService.archiveWallet(userId, walletId);
    }

    public List<KfeWalletNameOption> walletNames() {
        return walletService.availableWalletNames();
    }

    public KfeAddressResponse rotateAddress(Long userId, UUID walletId) {
        return walletService.rotateAddress(userId, walletId);
    }

    public List<KfeUtxoResponse> walletUtxos(Long userId, UUID walletId) {
        return walletNetworkService.listUtxos(userId, walletId);
    }

    public KfeColdWalletPsbtResponse createColdWalletPsbt(
            Long userId,
            UUID walletId,
            KfeColdWalletPsbtRequest request) {
        return walletNetworkService.createColdWalletPsbt(userId, walletId, request);
    }

    public KfeReceivingCapabilitiesResponse receivingCapabilities(String receiverIdentifier) {
        return walletNetworkService.receivingCapabilities(receiverIdentifier);
    }
}
