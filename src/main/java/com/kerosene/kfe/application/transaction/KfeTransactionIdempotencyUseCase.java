package com.kerosene.kfe.application.transaction;

import org.springframework.stereotype.Service;
import com.kerosene.kfe.dto.KfeSubmitTransactionRequest;
import com.kerosene.kfe.dto.KfeTransactionResponse;
import com.kerosene.kfe.model.KfeIdempotencyEntity;
import com.kerosene.kfe.model.KfeIdempotencyId;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.repository.KfeIdempotencyRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.service.KfeHashService;
import com.kerosene.kfe.service.KfeResponseMapper;

@Service
public class KfeTransactionIdempotencyUseCase {

    private final KfeIdempotencyRepository idempotencyRepository;
    private final KfeTransactionRepository transactionRepository;
    private final KfeHashService hashService;
    private final KfeResponseMapper responseMapper;

    public KfeTransactionIdempotencyUseCase(
            KfeIdempotencyRepository idempotencyRepository,
            KfeTransactionRepository transactionRepository,
            KfeHashService hashService,
            KfeResponseMapper responseMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.transactionRepository = transactionRepository;
        this.hashService = hashService;
        this.responseMapper = responseMapper;
    }

    public KfeIdempotencyEntity find(Long userId, String idempotencyKey) {
        return idempotencyRepository.findById(new KfeIdempotencyId(userId, idempotencyKey)).orElse(null);
    }

    public KfeTransactionResponse existingResponse(KfeIdempotencyEntity idempotency, String requestHash) {
        if (!idempotency.getRequestHash().equals(requestHash)) {
            throw new IllegalStateException("Idempotency key was reused with a different transaction payload.");
        }
        if (idempotency.getTransactionId() == null) {
            throw new IllegalStateException("Transaction is currently being processed. Please retry.");
        }
        return transactionRepository.findById(idempotency.getTransactionId())
                .map(responseMapper::toTransactionResponse)
                .orElseThrow(() -> new IllegalStateException("Idempotent transaction record is missing."));
    }

    public KfeTransactionResponse getExistingByIdempotency(Long userId, String idempotencyKey, String requestHash) {
        KfeIdempotencyEntity existingIdempotency = idempotencyRepository.findById(new KfeIdempotencyId(userId, idempotencyKey))
                .orElseThrow(() -> new IllegalStateException("Idempotency conflict detected, but no record found."));
        if (!existingIdempotency.getRequestHash().equals(requestHash)) {
            throw new IllegalStateException("Idempotency key was reused with a different transaction payload.");
        }
        if (existingIdempotency.getTransactionId() == null) {
            throw new IllegalStateException("Transaction is currently being processed. Please retry.");
        }
        return transactionRepository.findById(existingIdempotency.getTransactionId())
                .map(responseMapper::toTransactionResponse)
                .orElseThrow(() -> new IllegalStateException("Idempotent transaction record is missing."));
    }

    public KfeIdempotencyEntity reserve(Long userId, KfeSubmitTransactionRequest request, String requestHash) {
        KfeIdempotencyEntity idempotency = new KfeIdempotencyEntity();
        idempotency.setId(new KfeIdempotencyId(userId, request.idempotencyKey()));
        idempotency.setRequestHash(requestHash);
        idempotency.setStatus("PENDING");
        return idempotencyRepository.save(idempotency);
    }

    public void complete(KfeIdempotencyEntity idempotency, KfeTransactionEntity tx) {
        idempotency.setTransactionId(tx.getId());
        idempotency.setStatus(tx.getStatus().name());
        idempotencyRepository.save(idempotency);
    }

    public String requestHash(Long userId, KfeSubmitTransactionRequest request) {
        return hashService.sha256(String.join("|",
                "KFE_TX_REQUEST",
                userId.toString(),
                request.rail().name(),
                request.direction().name(),
                String.valueOf(request.sourceWalletId()),
                String.valueOf(request.destinationWalletId()),
                String.valueOf(request.amountSats()),
                String.valueOf(request.networkFeeSats()),
                safe(request.externalReference()),
                safe(request.paymentRequestPublicId()),
                safe(request.memo())));
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
