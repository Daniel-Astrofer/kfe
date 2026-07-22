package source.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "user_statement_24h",
        schema = "financial",
        indexes = {
                @Index(name = "idx_user_statement_24h_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_user_statement_24h_expiry", columnList = "expires_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_user_statement_24h_user_tx",
                        columnNames = {"user_id", "transaction_id"})
        })
public class KfeUserStatementEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /**
     * Assigned UUIDs make Spring Data treat entities as existing (merge/INSERT by PK).
     * Track true first insert so JPA save uses persist when appropriate.
     */
    @Transient
    private boolean isNew = true;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "wallet_id")
    private UUID walletId;

    @Column(name = "display_payload_json", nullable = false, columnDefinition = "TEXT")
    private String displayPayloadJson;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * Fixed order key — set once from the ledger {@code transactions_master.created_at}
     * (or first insert time). Never updated on status refresh.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PostLoad
    void onLoad() {
        isNew = false;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        isNew = false;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** Mark this instance as a first insert (required when id is pre-assigned). */
    public void markNew() {
        this.isNew = true;
    }

    /** Mark as existing after load from the database. */
    public void markNotNew() {
        this.isNew = false;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public String getDisplayPayloadJson() {
        return displayPayloadJson;
    }

    public void setDisplayPayloadJson(String displayPayloadJson) {
        this.displayPayloadJson = displayPayloadJson;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
