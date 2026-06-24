package source.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "balance_movements", schema = "financial", indexes = {
        @Index(name = "idx_balance_movements_transaction", columnList = "transaction_id, created_at"),
        @Index(name = "idx_balance_movements_wallet", columnList = "wallet_id, created_at")
})
public class KfeBalanceMovementEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "movement_type", nullable = false, length = 32)
    private String movementType;

    @Column(name = "amount_sats", nullable = false)
    private long amountSats;

    @Column(name = "from_bucket", length = 32)
    private String fromBucket;

    @Column(name = "to_bucket", length = 32)
    private String toBucket;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
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

    public UUID getWalletId() {
        return walletId;
    }

    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    public String getMovementType() {
        return movementType;
    }

    public void setMovementType(String movementType) {
        this.movementType = movementType;
    }

    public long getAmountSats() {
        return amountSats;
    }

    public void setAmountSats(long amountSats) {
        this.amountSats = amountSats;
    }

    public String getFromBucket() {
        return fromBucket;
    }

    public void setFromBucket(String fromBucket) {
        this.fromBucket = fromBucket;
    }

    public String getToBucket() {
        return toBucket;
    }

    public void setToBucket(String toBucket) {
        this.toBucket = toBucket;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
