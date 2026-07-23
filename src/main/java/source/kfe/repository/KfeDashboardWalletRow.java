package source.kfe.repository;

import java.time.LocalDateTime;
import java.util.UUID;

public interface KfeDashboardWalletRow {
    UUID getWalletId();

    String getKind();

    String getStatus();

    String getLabel();

    String getAsset();

    Boolean getSpendable();

    Long getAvailableSats();

    Long getPendingSats();

    Long getLockedSats();

    Long getAutoHoldSats();

    Long getObservedSats();

    String getActiveAddress();

    LocalDateTime getCreatedAt();

    LocalDateTime getUpdatedAt();
}
