package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestLinkExecution;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestLinkSnapshot;
import java.util.UUID;

public interface PaymentRequestLinkStatePort {
    PaymentRequestLinkSnapshot lockByPublicId(String publicId);
    PaymentRequestLinkSnapshot lockById(long recipientUserId, UUID requestId);
    PaymentRequestLinkExecution lockExecution(long payerUserId, PaymentExecutionId executionId);
    void markPaid(PaymentRequestLinkSnapshot previous, PaymentExecutionId executionId);
}
