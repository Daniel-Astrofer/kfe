package source.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transaction_idempotency", schema = "financial")
public class KfeIdempotencyEntity {

    @jakarta.persistence.EmbeddedId
    private KfeIdempotencyId id;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public KfeIdempotencyId getId() {
        return id;
    }

    public void setId(KfeIdempotencyId id) {
        this.id = id;
    }

    public String getIdempotencyKey() {
        return id != null ? id.getIdempotencyKey() : null;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        if (this.id == null) {
            this.id = new KfeIdempotencyId();
        }
        this.id.setIdempotencyKey(idempotencyKey);
    }

    public Long getUserId() {
        return id != null ? id.getUserId() : null;
    }

    public void setUserId(Long userId) {
        if (this.id == null) {
            this.id = new KfeIdempotencyId();
        }
        this.id.setUserId(userId);
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
