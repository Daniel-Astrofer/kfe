package com.kerosene.kfe.paymentexecution.adapters.in.compatibility;

import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentSubmissionMapper;
import com.kerosene.kfe.paymentexecution.application.port.in.AuthorizePaymentUseCase;

/** Compatibility bridge; policy and factor ordering belong to the payment execution core. */
@Service
public class KfeTransactionAuthorizationUseCase {

    private final AuthorizePaymentUseCase authorization;

    public KfeTransactionAuthorizationUseCase(AuthorizePaymentUseCase authorization) {
        this.authorization = authorization;
    }

    public void authorize(Long userId, KfeSubmitTransactionRequest request, String deviceHash) {
        authorization.authorize(LegacyPaymentSubmissionMapper.toCommand(userId, request, deviceHash));
    }
}
