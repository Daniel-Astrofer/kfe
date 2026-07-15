package source.kfe.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record KfeDashboardWallet(
        UUID walletId,
        String kind,
        String status,
        String label,
        String walletName,
        String walletTypeDescription,
        String asset,
        boolean spendable,
        long availableSats,
        long pendingSats,
        long lockedSats,
        long autoHoldSats,
        long observedSats,
        String activeAddress,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
