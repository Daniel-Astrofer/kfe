package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentRequestLink;
import java.util.Optional;

public interface PaymentRequestLinkUseCase {
    Optional<PreparedPaymentRequestLink> prepare(PreparePaymentRequestLinkCommand command);
    void complete(CompletePaymentRequestLinkCommand command);
}
