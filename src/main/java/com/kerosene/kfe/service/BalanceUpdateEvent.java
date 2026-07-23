package com.kerosene.kfe.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Realtime balance snapshot. Legacy clients only read {@code newBalance}/{@code amount}/{@code context}.
 * New fields enable dual-ledger UIs (available vs observed) without overwriting the wrong bucket.
 */
public class BalanceUpdateEvent {
    private String walletId;
    private String walletName;
    private Long userId;
    private BigDecimal newBalance;
    private BigDecimal amount;
    private String context;
    private LocalDateTime timestamp;

    /** Wallet kind: INTERNAL, CUSTODIAL_ONCHAIN, WATCH_ONLY, … */
    private String kind;
    private Long availableSats;
    private Long lockedSats;
    private Long pendingSats;
    private Long observedSats;
    /** Primary display sats for this kind (cold=observed; else available). */
    private Long primarySats;
    /** Bucket that primarily changed: AVAILABLE, LOCKED, OBSERVED, PRIMARY. */
    private String bucket;

    public BalanceUpdateEvent(String walletId, String walletName, Long userId, BigDecimal newBalance,
            BigDecimal amount, String context) {
        this.walletId = walletId;
        this.walletName = walletName;
        this.userId = userId;
        this.newBalance = newBalance;
        this.amount = amount;
        this.context = context;
        this.timestamp = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    public BalanceUpdateEvent(
            String walletId,
            String walletName,
            Long userId,
            BigDecimal newBalance,
            BigDecimal amount,
            String context,
            String kind,
            Long availableSats,
            Long lockedSats,
            Long pendingSats,
            Long observedSats,
            Long primarySats,
            String bucket) {
        this(walletId, walletName, userId, newBalance, amount, context);
        this.kind = kind;
        this.availableSats = availableSats;
        this.lockedSats = lockedSats;
        this.pendingSats = pendingSats;
        this.observedSats = observedSats;
        this.primarySats = primarySats;
        this.bucket = bucket;
    }

    public String getWalletId() {
        return walletId;
    }

    public void setWalletId(String walletId) {
        this.walletId = walletId;
    }

    public String getWalletName() {
        return walletName;
    }

    public void setWalletName(String walletName) {
        this.walletName = walletName;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getNewBalance() {
        return newBalance;
    }

    public void setNewBalance(BigDecimal newBalance) {
        this.newBalance = newBalance;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Long getAvailableSats() {
        return availableSats;
    }

    public void setAvailableSats(Long availableSats) {
        this.availableSats = availableSats;
    }

    public Long getLockedSats() {
        return lockedSats;
    }

    public void setLockedSats(Long lockedSats) {
        this.lockedSats = lockedSats;
    }

    public Long getPendingSats() {
        return pendingSats;
    }

    public void setPendingSats(Long pendingSats) {
        this.pendingSats = pendingSats;
    }

    public Long getObservedSats() {
        return observedSats;
    }

    public void setObservedSats(Long observedSats) {
        this.observedSats = observedSats;
    }

    public Long getPrimarySats() {
        return primarySats;
    }

    public void setPrimarySats(Long primarySats) {
        this.primarySats = primarySats;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }
}
