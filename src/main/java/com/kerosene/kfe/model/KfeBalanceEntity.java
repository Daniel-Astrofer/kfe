package com.kerosene.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    @Column(name = "reorg_debt_sats", nullable = false)
    private long reorgDebtSats;

    /** Last successful observed probe quality (LIVE_MEMPOOL_AWARE, OPTIMISTIC_DELTA, …). */
    @Column(name = "observed_probe_quality", length = 32)
    private String observedProbeQuality;

    @Column(name = "observed_probe_at")
    private LocalDateTime observedProbeAt;

    @Column(name = "observed_probe_source", length = 96)
    private String observedProbeSource;

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
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
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
        long debtPayment = Math.min(reorgDebtSats, amountSats);
        reorgDebtSats -= debtPayment;
        availableSats = Math.addExact(availableSats, amountSats - debtPayment);
        nonce++;
    }

    public void reverseAvailableCreditForReorg(long amountSats) {
        requirePositive(amountSats);
        long availableDebit = Math.min(availableSats, amountSats);
        availableSats -= availableDebit;
        reorgDebtSats = Math.addExact(reorgDebtSats, amountSats - availableDebit);
        nonce++;
    }

    public void setObservedBalance(long observedSats) {
        if (observedSats < 0) {
            throw new IllegalArgumentException("observedSats must be non-negative.");
        }
        this.observedSats = observedSats;
        nonce++;
    }

    public void setObservedProbeMeta(String quality, LocalDateTime probedAt, String source) {
        this.observedProbeQuality = quality;
        this.observedProbeAt = probedAt;
        this.observedProbeSource = source;
    }

    public String getObservedProbeQuality() {
        return observedProbeQuality;
    }

    public void setObservedProbeQuality(String observedProbeQuality) {
        this.observedProbeQuality = observedProbeQuality;
    }

    public LocalDateTime getObservedProbeAt() {
        return observedProbeAt;
    }

    public void setObservedProbeAt(LocalDateTime observedProbeAt) {
        this.observedProbeAt = observedProbeAt;
    }

    public String getObservedProbeSource() {
        return observedProbeSource;
    }

    public void setObservedProbeSource(String observedProbeSource) {
        this.observedProbeSource = observedProbeSource;
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

    public long getReorgDebtSats() {
        return reorgDebtSats;
    }

    public void setReorgDebtSats(long reorgDebtSats) {
        if (reorgDebtSats < 0L) {
            throw new IllegalArgumentException("reorgDebtSats must be non-negative.");
        }
        this.reorgDebtSats = reorgDebtSats;
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
