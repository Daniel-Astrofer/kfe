package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentPricingCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSubmissionPricing;

public interface PreparePaymentPricingUseCase {
    PaymentSubmissionPricing prepare(PreparePaymentPricingCommand command);
}
