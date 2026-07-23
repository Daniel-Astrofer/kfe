package source.kfe.dto;

import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;

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
