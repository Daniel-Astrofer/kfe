package com.kerosene.kfe.dto;

import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.model.KfeWalletStatus;

import java.time.Instant;
import java.util.UUID;

public record KfeWalletResponse(
        UUID id,
        KfeWalletKind kind,
        KfeWalletStatus status,
        String label,
        String walletName,
        String walletTypeDescription,
        String asset,
        boolean spendable,
        boolean xpubConfigured,
        boolean mpcKeyConfigured,
        String activeAddress,
        Instant createdAt,
        Instant updatedAt) {
}
