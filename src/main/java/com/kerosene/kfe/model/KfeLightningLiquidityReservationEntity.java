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
@Table(name = "lightning_liquidity_reservations", schema = "financial")
public class KfeLightningLiquidityReservationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "amount_sats", nullable = false)
    private long amountSats;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfeLiquidityReservationStatus status = KfeLiquidityReservationStatus.HELD;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

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

    public void markReleased() {
        status = KfeLiquidityReservationStatus.RELEASED;
        releasedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    public void markConsumed() {
        status = KfeLiquidityReservationStatus.CONSUMED;
        releasedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public long getAmountSats() {
        return amountSats;
    }

    public void setAmountSats(long amountSats) {
        this.amountSats = amountSats;
    }

    public KfeLiquidityReservationStatus getStatus() {
        return status;
    }

    public void setStatus(KfeLiquidityReservationStatus status) {
        this.status = status;
    }
}
