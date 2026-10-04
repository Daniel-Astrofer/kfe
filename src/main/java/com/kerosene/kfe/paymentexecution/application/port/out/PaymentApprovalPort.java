package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;

/** Authorizes the complete canonical request, not independently reusable factors. */
public interface PaymentApprovalPort {
    void approve(SubmitPaymentCommand command);
}
