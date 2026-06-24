package source.kfe.model;

public enum KfeTransactionStatus {
    INTENT,
    VALIDATING,
    QUORUM_SYNC,
    LOCKED,
    EXECUTING,
    SETTLED,
    FAILED,
    REQUIRES_RECONCILIATION
}
