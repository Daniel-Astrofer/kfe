package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.UUID;

public record PaymentExecutionId(UUID value) {
    public PaymentExecutionId {
        if (value == null) {
            throw new IllegalArgumentException("payment execution id is required");
        }
    }
}
