package com.kerosene.kfe.wallet.domain.model;

import java.util.UUID;

public record WalletSnapshot(
        UUID id,
        long ownerId,
        WalletKind kind,
        WalletStatus status,
        String asset,
        boolean spendable,
        boolean xpubConfigured,
        boolean descriptorConfigured,
        String xpub,
        String descriptor) {

    public WalletSnapshot {
        if (id == null || ownerId <= 0 || kind == null || status == null) {
            throw new IllegalArgumentException("Wallet identity and state are required.");
        }
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("Wallet asset is required.");
        }
        asset = asset.trim().toUpperCase(java.util.Locale.ROOT);
        xpub = xpub == null || xpub.isBlank() ? null : xpub.trim();
        descriptor = descriptor == null || descriptor.isBlank() ? null : descriptor.trim();
    }

    public boolean isActive() {
        return status == WalletStatus.ACTIVE;
    }

    public boolean isCold() {
        return kind == WalletKind.WATCH_ONLY;
    }
}
