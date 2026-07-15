package source.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "balances_core", schema = "financial")
public class KfeBalanceEntity {

    @EmbeddedId
    private KfeBalanceId id;

    @Column(name = "available_sats", nullable = false)
    private long availableSats;

    @Column(name = "pending_sats", nullable = false)
    private long pendingSats;

    @Column(name = "locked_sats", nullable = false)
    private long lockedSats;

    @Column(name = "auto_hold_sats", nullable = false)
    private long autoHoldSats;

    @Column(name = "observed_sats", nullable = false)
    private long observedSats;

    @Column(name = "nonce", nullable = false)
    private long nonce;

    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    @Column(name = "balance_signature", nullable = false, length = 256)
    private String balanceSignature;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static KfeBalanceEntity empty(UUID walletId, String asset, String initialHash) {
        KfeBalanceEntity entity = new KfeBalanceEntity();
        entity.setId(new KfeBalanceId(walletId, asset));
        entity.setLastHash(initialHash);
        entity.setBalanceSignature(initialHash);
        return entity;
    }

    @PrePersist
    @PreUpdate
    void onWrite() {
        updatedAt = LocalDateTime.now();
    }

    public void reserve(long amountSats) {
        requirePositive(amountSats);
        if (availableSats < amountSats) {
            throw new IllegalStateException("Insufficient available balance.");
        }
        availableSats -= amountSats;
        lockedSats = Math.addExact(lockedSats, amountSats);
        nonce++;
    }

    public void settleReservedDebit(long amountSats) {
        requirePositive(amountSats);
        if (lockedSats < amountSats) {
            throw new IllegalStateException("Insufficient locked balance.");
        }
        lockedSats -= amountSats;
        nonce++;
    }

    public void releaseReserved(long amountSats) {
        requirePositive(amountSats);
        if (lockedSats < amountSats) {
            throw new IllegalStateException("Insufficient locked balance.");
        }
        lockedSats -= amountSats;
        availableSats = Math.addExact(availableSats, amountSats);
        nonce++;
    }

    public void creditAvailable(long amountSats) {
        requirePositive(amountSats);
        availableSats = Math.addExact(availableSats, amountSats);
        nonce++;
    }

    public void setObservedBalance(long observedSats) {
        if (observedSats < 0) {
            throw new IllegalArgumentException("observedSats must be non-negative.");
        }
        this.observedSats = observedSats;
        nonce++;
    }

    private void requirePositive(long amountSats) {
        if (amountSats <= 0) {
            throw new IllegalArgumentException("amountSats must be positive.");
        }
    }

    public KfeBalanceId getId() {
        return id;
    }

    public void setId(KfeBalanceId id) {
        this.id = id;
    }

    public long getAvailableSats() {
        return availableSats;
    }

    public void setAvailableSats(long availableSats) {
        this.availableSats = availableSats;
    }

    public long getPendingSats() {
        return pendingSats;
    }

    public void setPendingSats(long pendingSats) {
        this.pendingSats = pendingSats;
    }

    public long getLockedSats() {
        return lockedSats;
    }

    public void setLockedSats(long lockedSats) {
        this.lockedSats = lockedSats;
    }

    public long getAutoHoldSats() {
        return autoHoldSats;
    }

    public void setAutoHoldSats(long autoHoldSats) {
        this.autoHoldSats = autoHoldSats;
    }

    public long getObservedSats() {
        return observedSats;
    }

    public void setObservedSats(long observedSats) {
        this.observedSats = observedSats;
    }

    public long getNonce() {
        return nonce;
    }

    public void setNonce(long nonce) {
        this.nonce = nonce;
    }

    public String getLastHash() {
        return lastHash;
    }

    public void setLastHash(String lastHash) {
        this.lastHash = lastHash;
    }

    public String getBalanceSignature() {
        return balanceSignature;
    }

    public void setBalanceSignature(String balanceSignature) {
        this.balanceSignature = balanceSignature;
    }

    public Long getVersion() {
        return version;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
