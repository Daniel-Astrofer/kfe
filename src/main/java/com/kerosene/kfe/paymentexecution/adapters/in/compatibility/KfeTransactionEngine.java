package com.kerosene.kfe.paymentexecution.adapters.in.compatibility;

import org.springframework.stereotype.Service;
import com.kerosene.kfe.paymentexecution.adapters.in.compatibility.KfeSubmitTransactionUseCase;
import com.kerosene.kfe.paymentexecution.adapters.in.compatibility.KfeTransactionIdempotencyUseCase;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;

@Service
public class KfeTransactionEngine {

    private final KfeSubmitTransactionUseCase submitTransactionUseCase;
    private final KfeTransactionIdempotencyUseCase idempotencyUseCase;

    public KfeTransactionEngine(
            KfeSubmitTransactionUseCase submitTransactionUseCase,
            KfeTransactionIdempotencyUseCase idempotencyUseCase) {
        this.submitTransactionUseCase = submitTransactionUseCase;
        this.idempotencyUseCase = idempotencyUseCase;
    }

    public KfeTransactionResponse submit(Long userId, KfeSubmitTransactionRequest request) {
        return submit(userId, request, null);
    }

    public KfeTransactionResponse submit(Long userId, KfeSubmitTransactionRequest request, String deviceHash) {
        return submitTransactionUseCase.submit(userId, request, deviceHash);
    }

    public KfeTransactionResponse getExistingByIdempotency(Long userId, String idempotencyKey, String requestHash) {
        return idempotencyUseCase.getExistingByIdempotency(userId, idempotencyKey, requestHash);
    }

    public String requestHash(Long userId, KfeSubmitTransactionRequest request) {
        return idempotencyUseCase.requestHash(userId, request);
    }
}
