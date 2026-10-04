package com.kerosene.kfe.paymentexecution.application.result;

/** Routing may rewrite only these two fields, never identity, value or credentials. */
public record CanonicalPaymentDestination(String externalReference, String memo) {
    @Override
    public String toString() { return "CanonicalPaymentDestination[REDACTED]"; }
}
