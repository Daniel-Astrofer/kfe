package com.kerosene.kfe.application.financial;

import java.time.ZoneOffset;


import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import com.kerosene.kfe.dto.KfeAddressResponse;
import com.kerosene.kfe.dto.KfeColdWalletPsbtRequest;
import com.kerosene.kfe.dto.KfeColdWalletPsbtResponse;
import com.kerosene.kfe.dto.KfeCreateWalletRequest;
import com.kerosene.kfe.dto.KfeReceivingCapabilitiesResponse;
import com.kerosene.kfe.dto.KfeSubmitTransactionRequest;
import com.kerosene.kfe.dto.KfeTransactionResponse;
import com.kerosene.kfe.dto.KfeTransactionQuoteRequest;
import com.kerosene.kfe.dto.KfeTransactionQuoteResponse;
import com.kerosene.kfe.dto.KfeUpdateWalletRequest;
import com.kerosene.kfe.dto.KfeUtxoResponse;
import com.kerosene.kfe.dto.KfeWalletNameOption;
import com.kerosene.kfe.dto.KfeWalletResponse;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.service.KfeResponseMapper;
import com.kerosene.kfe.service.KfePricingService;
import com.kerosene.kfe.service.KfeNetworkFeeEstimateService;
import com.kerosene.kfe.service.KfeTransactionCancellationService;
import com.kerosene.kfe.service.KfeTransactionEngine;
import com.kerosene.kfe.service.KfeWalletNetworkService;
import com.kerosene.kfe.service.KfeWalletService;

import java.util.List;
import java.util.UUID;

@Service
public class FinancialApi {

    private final KfeTransactionEngine transactionEngine;
    private final KfeTransactionRepository transactionRepository;
    private final KfeResponseMapper responseMapper;
    private final KfePricingService pricingService;
    private final KfeNetworkFeeEstimateService networkFeeEstimateService;
    private final KfeWalletService walletService;
    private final KfeWalletNetworkService walletNetworkService;
    private final KfeTransactionCancellationService transactionCancellationService;

    public FinancialApi(
            KfeTransactionEngine transactionEngine,
            KfeTransactionRepository transactionRepository,
            KfeResponseMapper responseMapper,
            KfePricingService pricingService,
            KfeNetworkFeeEstimateService networkFeeEstimateService,
            KfeWalletService walletService,
            KfeWalletNetworkService walletNetworkService,
            KfeTransactionCancellationService transactionCancellationService) {
        this.transactionEngine = transactionEngine;
        this.transactionRepository = transactionRepository;
        this.responseMapper = responseMapper;
        this.pricingService = pricingService;
        this.networkFeeEstimateService = networkFeeEstimateService;
        this.walletService = walletService;
        this.walletNetworkService = walletNetworkService;
        this.transactionCancellationService = transactionCancellationService;
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
        KfeNetworkFeeEstimateService.Estimate feeEstimate = networkFeeEstimateService.estimate(
                request.rail(),
                request.direction(),
                request.networkFeeSats());
        KfePricingService.Quote quote = pricingService.quote(
                request.rail(),
                request.direction(),
                request.amountSats(),
                feeEstimate.selectedNetworkFeeSats());
        return new KfeTransactionQuoteResponse(
                request.rail(),
                request.direction(),
                quote.grossAmountSats(),
                quote.receiverAmountSats(),
                quote.networkFeeSats(),
                quote.totalDebitSats(),
                quote.keroseneFeeSats(),
                Math.addExact(quote.networkFeeSats(), quote.keroseneFeeSats()),
                feeEstimate.selectedFeeRateSatPerVbyte(),
                feeEstimate.estimatedVbytes(),
                feeEstimate.selectedTargetBlocks(),
                feeEstimate.selectedEstimatedSeconds(),
                feeEstimate.selectedSource(),
                feeEstimate.expiresAt(),
                feeEstimate.tiers());
    }

    public KfeTransactionResponse transaction(Long userId, UUID transactionId) {
        return transactionRepository
                .findParticipantVisibleById(transactionId, userId, KfeRail.INTERNAL, KfeDirection.INTERNAL)
                .map(transaction -> responseMapper.toTransactionResponse(transaction, userId))
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
    }

    /**
     * Cancels a pending invoice/payment-link or abandonable pre-settlement transaction so it
     * stops hanging as PENDING in history ({@code displayStatus=FAILED}, failureCode USER_CANCELLED).
     */
    public KfeTransactionResponse cancelTransaction(Long userId, UUID transactionId) {
        return transactionCancellationService.cancelTransaction(userId, transactionId);
    }

    public List<KfeTransactionResponse> transactions(Long userId, int page, int size) {
        return transactions(userId, page, size, null);
    }

    /**
     * Lists participant-visible transactions.
     *
     * @param since when non-null, only rows with {@code updatedAt > since} (UTC) — incremental
     *              sync for clients that already hold a durable local projection.
     */
    public List<KfeTransactionResponse> transactions(
            Long userId, int page, int size, java.time.Instant since) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(200, Math.max(1, size));
        var pageable = PageRequest.of(safePage, safeSize);
        var rows = since == null
                ? transactionRepository.findParticipantVisibleByUserId(
                        userId, KfeRail.INTERNAL, KfeDirection.INTERNAL, pageable)
                : transactionRepository.findParticipantVisibleByUserIdSince(
                        userId,
                        KfeRail.INTERNAL,
                        KfeDirection.INTERNAL,
                        java.time.LocalDateTime.ofInstant(since, java.time.ZoneOffset.UTC),
                        pageable);
        return rows.stream()
                .map(transaction -> responseMapper.toTransactionResponse(transaction, userId))
                .toList();
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

    public KfeReceivingCapabilitiesResponse receivingCapabilities(
            Long senderUserId,
            String receiverIdentifier) {
        return walletNetworkService.receivingCapabilities(senderUserId, receiverIdentifier);
    }
}