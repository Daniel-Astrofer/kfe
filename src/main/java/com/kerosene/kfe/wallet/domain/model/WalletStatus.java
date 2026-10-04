package com.kerosene.kfe.wallet.domain.model;

public enum WalletStatus {
    CREATING,
    ACTIVE,
    FROZEN,
    ROTATING_ADDRESS,
    KEYGEN_FAILED,
    QUORUM_BLOCKED,
    ARCHIVED
}
