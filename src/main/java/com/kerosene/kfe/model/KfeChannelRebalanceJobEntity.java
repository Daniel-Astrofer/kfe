package com.kerosene.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "channel_rebalance_jobs", schema = "financial")
public class KfeChannelRebalanceJobEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "decision_id")
    private UUID decisionId;

    @Column(name = "channel_point", nullable = false, length = 128)
    private String channelPoint;

    @Column(name = "peer_pubkey", length = 128)
    private String peerPubkey;

    @Column(name = "estimated_cost_sats", nullable = false)
    private long estimatedCostSats;

    @Column(name = "expected_gain_sats", nullable = false)
    private long expectedGainSats;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfeChannelRebalanceJobStatus status = KfeChannelRebalanceJobStatus.PENDING;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    public void markInProgress(String providerReference) {
        status = KfeChannelRebalanceJobStatus.IN_PROGRESS;
        this.providerReference = providerReference;
    }

    public void markCompleted(String providerReference) {
        status = KfeChannelRebalanceJobStatus.COMPLETED;
        this.providerReference = providerReference;
        completedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    public void markFailed(String error) {
        status = KfeChannelRebalanceJobStatus.FAILED;
        lastError = error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
        completedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public void setDecisionId(UUID decisionId) {
        this.decisionId = decisionId;
    }

    public String getChannelPoint() {
        return channelPoint;
    }

    public void setChannelPoint(String channelPoint) {
        this.channelPoint = channelPoint;
    }

    public String getPeerPubkey() {
        return peerPubkey;
    }

    public void setPeerPubkey(String peerPubkey) {
        this.peerPubkey = peerPubkey;
    }

    public long getEstimatedCostSats() {
        return estimatedCostSats;
    }

    public void setEstimatedCostSats(long estimatedCostSats) {
        this.estimatedCostSats = estimatedCostSats;
    }

    public long getExpectedGainSats() {
        return expectedGainSats;
    }

    public void setExpectedGainSats(long expectedGainSats) {
        this.expectedGainSats = expectedGainSats;
    }

    public KfeChannelRebalanceJobStatus getStatus() {
        return status;
    }

    public void setStatus(KfeChannelRebalanceJobStatus status) {
        this.status = status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
