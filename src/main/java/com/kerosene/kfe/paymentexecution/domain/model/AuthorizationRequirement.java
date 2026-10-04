package com.kerosene.kfe.paymentexecution.domain.model;

public enum AuthorizationRequirement {
    NONE,
    WALLET_OUTBOUND_STEP_UP,
    LOCAL_FACTOR_AND_ASSERTION
}
