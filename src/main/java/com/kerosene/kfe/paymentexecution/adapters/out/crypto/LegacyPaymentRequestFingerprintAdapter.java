package com.kerosene.kfe.paymentexecution.adapters.out.crypto;

import com.kerosene.kfe.paymentexecution.adapters.in.compatibility.KfeTransactionIdempotencyUseCase;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentSubmissionMapper;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestFingerprintPort;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import org.springframework.stereotype.Component;

/** Uses the same hash implementation as the existing engine compatibility facade. */
@Component
public class LegacyPaymentRequestFingerprintAdapter implements PaymentRequestFingerprintPort {
    private final KfeTransactionIdempotencyUseCase delegate;

    public LegacyPaymentRequestFingerprintAdapter(KfeTransactionIdempotencyUseCase delegate) {
        this.delegate = delegate;
    }

    @Override
    public RequestFingerprint fingerprint(SubmitPaymentCommand command) {
        return new RequestFingerprint(delegate.requestHash(command.userId(),
                LegacyPaymentSubmissionMapper.toLegacyRequest(command)));
    }
}
