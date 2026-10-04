package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;

public interface PaymentRequestFingerprintPort {
    RequestFingerprint fingerprint(SubmitPaymentCommand command);
}
