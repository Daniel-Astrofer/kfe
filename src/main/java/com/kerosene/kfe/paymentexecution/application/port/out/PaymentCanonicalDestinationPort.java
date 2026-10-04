package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.CanonicalPaymentDestination;

public interface PaymentCanonicalDestinationPort {
    CanonicalPaymentDestination resolve(SubmitPaymentCommand command);
}
