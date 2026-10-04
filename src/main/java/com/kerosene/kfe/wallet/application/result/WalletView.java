package com.kerosene.kfe.wallet.application.result;

import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;

import java.time.Instant;
import java.util.UUID;

/** Stable application view used by inbound adapters; it contains no API DTOs. */
public record WalletView(
        UUID id,
        WalletKind kind,
        WalletStatus status,
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
