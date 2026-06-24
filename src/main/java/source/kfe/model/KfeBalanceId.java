package source.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class KfeBalanceId implements Serializable {

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "asset", nullable = false, length = 16)
    private String asset = "BTC";

    public KfeBalanceId() {
    }

    public KfeBalanceId(UUID walletId, String asset) {
        this.walletId = walletId;
        this.asset = asset != null ? asset : "BTC";
    }

    public UUID getWalletId() {
        return walletId;
    }

    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    public String getAsset() {
        return asset;
    }

    public void setAsset(String asset) {
        this.asset = asset;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KfeBalanceId that)) {
            return false;
        }
        return Objects.equals(walletId, that.walletId) && Objects.equals(asset, that.asset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(walletId, asset);
    }
}
