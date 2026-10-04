package com.kerosene.kfe.paymentexecution.domain.model;

/** Presence-only evidence; raw prepared operations and provider identifiers stay outside the domain. */
public record ExternalExecutionEvidence(
        boolean preparedCiphertextPresent,
        boolean preparedHashPresent,
        boolean executionReferencePresent,
        boolean outboxProviderReferencePresent,
        boolean transactionProviderReferencePresent,
        boolean blockchainTxidPresent,
        boolean paymentHashPresent) {
}
