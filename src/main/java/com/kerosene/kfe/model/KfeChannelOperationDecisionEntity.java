package com.kerosene.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "channel_operation_decisions", schema = "financial")
public class KfeChannelOperationDecisionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 32)
    private KfeChannelOperationType operation;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    @Column(name = "peer_pubkey", length = 128)
    private String peerPubkey;

    @Column(name = "channel_point", length = 128)
    private String channelPoint;

    @Column(name = "amount_sats")
    private Long amountSats;

    @Column(name = "flags_json", nullable = false, columnDefinition = "TEXT")
    private String flagsJson;

    @Column(name = "decision_reason", length = 255)
    private String decisionReason;

    @Column(name = "executed", nullable = false)
    private boolean executed;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    public UUID getId() {
        return id;
    }

    public KfeChannelOperationType getOperation() {
        return operation;
    }

    public void setOperation(KfeChannelOperationType operation) {
        this.operation = operation;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public String getPeerPubkey() {
        return peerPubkey;
    }

    public void setPeerPubkey(String peerPubkey) {
        this.peerPubkey = peerPubkey;
    }

    public String getChannelPoint() {
        return channelPoint;
    }

    public void setChannelPoint(String channelPoint) {
        this.channelPoint = channelPoint;
    }

    public Long getAmountSats() {
        return amountSats;
    }

    public void setAmountSats(Long amountSats) {
        this.amountSats = amountSats;
    }

    public String getFlagsJson() {
        return flagsJson;
    }

    public void setFlagsJson(String flagsJson) {
        this.flagsJson = flagsJson;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public boolean isExecuted() {
        return executed;
    }

    public void setExecuted(boolean executed) {
        this.executed = executed;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
