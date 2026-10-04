package com.kerosene.kfe.paymentexecution.application.result;

import java.util.Objects;

/** Provider-neutral observation. Payload is internal reconciliation data, never a log message. */
public record ExternalExecutionResult(Outcome outcome, String providerReference, String rawPayload) {
    public enum Outcome { COMPLETED, CLAIM_LOST, UNKNOWN, RETRYABLE_FAILURE, FINAL_FAILURE }

    public ExternalExecutionResult {
        Objects.requireNonNull(outcome, "outcome is required");
        if (outcome != Outcome.UNKNOWN && (providerReference != null || rawPayload != null)) {
            throw new IllegalArgumentException("Only an uncertain outcome carries reconciliation data.");
        }
    }

    public static ExternalExecutionResult completed() { return new ExternalExecutionResult(Outcome.COMPLETED, null, null); }
    public static ExternalExecutionResult claimLost() { return new ExternalExecutionResult(Outcome.CLAIM_LOST, null, null); }
    public static ExternalExecutionResult unknown(String reference, String payload) { return new ExternalExecutionResult(Outcome.UNKNOWN, reference, payload); }
    public static ExternalExecutionResult retryableFailure() { return new ExternalExecutionResult(Outcome.RETRYABLE_FAILURE, null, null); }
    public static ExternalExecutionResult finalFailure() { return new ExternalExecutionResult(Outcome.FINAL_FAILURE, null, null); }

    public String message() {
        return switch (outcome) {
            case UNKNOWN -> "External execution outcome requires reconciliation.";
            case RETRYABLE_FAILURE -> "External execution preparation failed; retry is required.";
            case FINAL_FAILURE -> "External execution preparation was rejected.";
            case COMPLETED, CLAIM_LOST -> null;
        };
    }

    @Override public String toString() { return "ExternalExecutionResult[outcome=" + outcome + "]"; }
}
