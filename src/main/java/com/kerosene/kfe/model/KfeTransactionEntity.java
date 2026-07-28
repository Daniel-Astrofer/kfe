package com.kerosene.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "transactions_master", schema = "financial", indexes = {
        @Index(name = "idx_transactions_master_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_transactions_master_status", columnList = "status"),
        @Index(name = "idx_transactions_master_provider_reference", columnList = "provider_reference, status")
})
public class KfeTransactionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 180)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source_wallet_id")
    private UUID sourceWalletId;

    @Column(name = "destination_wallet_id")
    private UUID destinationWalletId;

    @Column(name = "external_reference", columnDefinition = "TEXT")
    private String externalReference;

    @Column(name = "memo", length = 255)
    private String memo;

    @Enumerated(EnumType.STRING)
    @Column(name = "rail", nullable = false, length = 32)
    private KfeRail rail;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 32)
    private KfeDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfeTransactionStatus status = KfeTransactionStatus.INTENT;

    @Column(name = "gross_amount_sats", nullable = false)
    private long grossAmountSats;

    @Column(name = "receiver_amount_sats", nullable = false)
    private long receiverAmountSats;

    @Column(name = "network_fee_sats", nullable = false)
    private long networkFeeSats;

    @Column(name = "kerosene_fee_sats", nullable = false)
    private long keroseneFeeSats;

    @Column(name = "total_debit_sats", nullable = false)
    private long totalDebitSats;

    @Column(name = "pricing_policy_version", nullable = false)
    private int pricingPolicyVersion;

    @Column(name = "display_btc_usd", precision = 19, scale = 8)
    private BigDecimal displayBtcUsd;

    @Column(name = "display_btc_eur", precision = 19, scale = 8)
    private BigDecimal displayBtcEur;

    @Column(name = "display_btc_brl", precision = 19, scale = 8)
    private BigDecimal displayBtcBrl;

    @Column(name = "display_amount_usd", precision = 19, scale = 2)
    private BigDecimal displayAmountUsd;

    @Column(name = "display_amount_eur", precision = 19, scale = 2)
    private BigDecimal displayAmountEur;

    @Column(name = "display_amount_brl", precision = 19, scale = 2)
    private BigDecimal displayAmountBrl;

    @Column(name = "quorum_proposal_hash", length = 64)
    private String quorumProposalHash;

    @Column(name = "quorum_ack_count", nullable = false)
    private int quorumAckCount;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "blockchain_txid", length = 128)
    private String blockchainTxid;

    @Column(name = "payment_hash", length = 128)
    private String paymentHash;

    @Column(name = "confirmations", nullable = false)
    private int confirmations;

    @Column(name = "business_status", length = 32)
    private String businessStatus;

    @Column(name = "network_status", length = 32)
    private String networkStatus;

    @Column(name = "accounting_status", length = 32)
    private String accountingStatus;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", length = 255)
    private String failureMessage;

    // ITEM 8: Network tracking for disappeared transactions
    @Column(name = "network_first_seen_at")
    private LocalDateTime networkFirstSeenAt;

    @Column(name = "network_last_seen_at")
    private LocalDateTime networkLastSeenAt;

    @Column(name = "network_not_found_since")
    private LocalDateTime networkNotFoundSince;

    @Column(name = "network_not_found_count")
    private int networkNotFoundCount;

    @Column(name = "mempool_last_seen_at")
    private LocalDateTime mempoolLastSeenAt;

    @Column(name = "last_chain_probe_at")
    private LocalDateTime lastChainProbeAt;

    @Column(name = "last_chain_probe_status", length = 32)
    private String lastChainProbeStatus;

    // ITEM 14: Store raw tx hash at preparation for settlement verification
    @Column(name = "prepared_raw_tx_hash", length = 64)
    private String preparedRawTxHash;

    // ITEM 8: Whether confirmation monitoring is active (for disappeared tx detection)
    @Column(name = "confirmation_monitoring_active", nullable = false)
    private boolean confirmationMonitoringActive = true;

    // ITEM 4: Track when conflicted state was entered
    @Column(name = "conflicted_at")
    private LocalDateTime conflictedAt;

    @Column(name = "replacement_txid", length = 128)
    private String replacementTxid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        // Always UTC wall clock — pods run Etc/UTC; never rely on host local TZ.
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public UUID getSourceWalletId() {
        return sourceWalletId;
    }

    public void setSourceWalletId(UUID sourceWalletId) {
        this.sourceWalletId = sourceWalletId;
    }

    public UUID getDestinationWalletId() {
        return destinationWalletId;
    }

    public void setDestinationWalletId(UUID destinationWalletId) {
        this.destinationWalletId = destinationWalletId;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public void setExternalReference(String externalReference) {
        this.externalReference = externalReference;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public KfeRail getRail() {
        return rail;
    }

    public void setRail(KfeRail rail) {
        this.rail = rail;
    }

    public KfeDirection getDirection() {
        return direction;
    }

    public void setDirection(KfeDirection direction) {
        this.direction = direction;
    }

    public KfeTransactionStatus getStatus() {
        return status;
    }

    public void setStatus(KfeTransactionStatus status) {
        this.status = status;
    }

    public long getGrossAmountSats() {
        return grossAmountSats;
    }

    public void setGrossAmountSats(long grossAmountSats) {
        this.grossAmountSats = grossAmountSats;
    }

    public long getReceiverAmountSats() {
        return receiverAmountSats;
    }

    public void setReceiverAmountSats(long receiverAmountSats) {
        this.receiverAmountSats = receiverAmountSats;
    }

    public long getNetworkFeeSats() {
        return networkFeeSats;
    }

    public void setNetworkFeeSats(long networkFeeSats) {
        this.networkFeeSats = networkFeeSats;
    }

    public long getKeroseneFeeSats() {
        return keroseneFeeSats;
    }

    public void setKeroseneFeeSats(long keroseneFeeSats) {
        this.keroseneFeeSats = keroseneFeeSats;
    }

    public long getTotalDebitSats() {
        return totalDebitSats;
    }

    public void setTotalDebitSats(long totalDebitSats) {
        this.totalDebitSats = totalDebitSats;
    }

    public int getPricingPolicyVersion() {
        return pricingPolicyVersion;
    }

    public void setPricingPolicyVersion(int pricingPolicyVersion) {
        this.pricingPolicyVersion = pricingPolicyVersion;
    }

    public BigDecimal getDisplayBtcUsd() {
        return displayBtcUsd;
    }

    public void setDisplayBtcUsd(BigDecimal displayBtcUsd) {
        this.displayBtcUsd = displayBtcUsd;
    }

    public BigDecimal getDisplayBtcEur() {
        return displayBtcEur;
    }

    public void setDisplayBtcEur(BigDecimal displayBtcEur) {
        this.displayBtcEur = displayBtcEur;
    }

    public BigDecimal getDisplayBtcBrl() {
        return displayBtcBrl;
    }

    public void setDisplayBtcBrl(BigDecimal displayBtcBrl) {
        this.displayBtcBrl = displayBtcBrl;
    }

    public BigDecimal getDisplayAmountUsd() {
        return displayAmountUsd;
    }

    public void setDisplayAmountUsd(BigDecimal displayAmountUsd) {
        this.displayAmountUsd = displayAmountUsd;
    }

    public BigDecimal getDisplayAmountEur() {
        return displayAmountEur;
    }

    public void setDisplayAmountEur(BigDecimal displayAmountEur) {
        this.displayAmountEur = displayAmountEur;
    }

    public BigDecimal getDisplayAmountBrl() {
        return displayAmountBrl;
    }

    public void setDisplayAmountBrl(BigDecimal displayAmountBrl) {
        this.displayAmountBrl = displayAmountBrl;
    }

    public String getQuorumProposalHash() {
        return quorumProposalHash;
    }

    public void setQuorumProposalHash(String quorumProposalHash) {
        this.quorumProposalHash = quorumProposalHash;
    }

    public int getQuorumAckCount() {
        return quorumAckCount;
    }

    public void setQuorumAckCount(int quorumAckCount) {
        this.quorumAckCount = quorumAckCount;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public String getBlockchainTxid() {
        return blockchainTxid;
    }

    public void setBlockchainTxid(String blockchainTxid) {
        this.blockchainTxid = blockchainTxid;
    }

    public String getPaymentHash() {
        return paymentHash;
    }

    public void setPaymentHash(String paymentHash) {
        this.paymentHash = paymentHash;
    }

    public int getConfirmations() {
        return confirmations;
    }

    public void setConfirmations(int confirmations) {
        this.confirmations = confirmations;
    }

    public String getBusinessStatus() {
        return businessStatus;
    }

    public void setBusinessStatus(String businessStatus) {
        this.businessStatus = businessStatus;
    }

    public String getNetworkStatus() {
        return networkStatus;
    }

    public void setNetworkStatus(String networkStatus) {
        this.networkStatus = networkStatus;
    }

    public String getAccountingStatus() {
        return accountingStatus;
    }

    public void setAccountingStatus(String accountingStatus) {
        this.accountingStatus = accountingStatus;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public LocalDateTime getNetworkFirstSeenAt() { return networkFirstSeenAt; }
    public void setNetworkFirstSeenAt(LocalDateTime v) { this.networkFirstSeenAt = v; }
    public LocalDateTime getNetworkLastSeenAt() { return networkLastSeenAt; }
    public void setNetworkLastSeenAt(LocalDateTime v) { this.networkLastSeenAt = v; }
    public LocalDateTime getNetworkNotFoundSince() { return networkNotFoundSince; }
    public void setNetworkNotFoundSince(LocalDateTime v) { this.networkNotFoundSince = v; }
    public int getNetworkNotFoundCount() { return networkNotFoundCount; }
    public void setNetworkNotFoundCount(int v) { this.networkNotFoundCount = v; }
    public LocalDateTime getMempoolLastSeenAt() { return mempoolLastSeenAt; }
    public void setMempoolLastSeenAt(LocalDateTime v) { this.mempoolLastSeenAt = v; }
    public LocalDateTime getLastChainProbeAt() { return lastChainProbeAt; }
    public void setLastChainProbeAt(LocalDateTime v) { this.lastChainProbeAt = v; }
    public String getLastChainProbeStatus() { return lastChainProbeStatus; }
    public void setLastChainProbeStatus(String v) { this.lastChainProbeStatus = v; }
    public String getPreparedRawTxHash() { return preparedRawTxHash; }
    public void setPreparedRawTxHash(String v) { this.preparedRawTxHash = v; }
    public boolean isConfirmationMonitoringActive() { return confirmationMonitoringActive; }
    public void setConfirmationMonitoringActive(boolean v) { this.confirmationMonitoringActive = v; }
    public LocalDateTime getConflictedAt() { return conflictedAt; }
    public void setConflictedAt(LocalDateTime v) { this.conflictedAt = v; }
    public String getReplacementTxid() { return replacementTxid; }
    public void setReplacementTxid(String v) { this.replacementTxid = v; }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
