package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

public interface PaymentDestinationValidationPort {
    void validate(PaymentRail rail, String externalReference);
}
