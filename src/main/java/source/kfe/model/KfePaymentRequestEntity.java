package source.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_requests", schema = "financial", indexes = {
        @Index(name = "idx_payment_requests_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_payment_requests_public_id", columnList = "public_id"),
        @Index(name = "idx_payment_requests_wallet_status", columnList = "wallet_id, status, created_at")
})
public class KfePaymentRequestEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "public_id", nullable = false, unique = true, length = 48)
    private String publicId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "address_id")
    private UUID addressId;

    @Column(name = "address", nullable = false, length = 128)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "rail", nullable = false, length = 32)
    private KfeRail rail = KfeRail.ONCHAIN;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfePaymentRequestStatus status = KfePaymentRequestStatus.OPEN;

    @Column(name = "amount_sats")
    private Long amountSats;

    @Column(name = "description", length = 180)
    private String description;

    @Column(name = "memo", length = 255)
    private String memo;

    @Column(name = "payer_hint", length = 120)
    private String payerHint;

    @Column(name = "paid_transaction_id")
    private UUID paidTransactionId;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isExpired(LocalDateTime now) {
        return status == KfePaymentRequestStatus.OPEN && expiresAt != null && expiresAt.isBefore(now);
    }

    public void expire() {
        status = KfePaymentRequestStatus.EXPIRED;
    }

    public void hide() {
        status = KfePaymentRequestStatus.HIDDEN;
        hiddenAt = LocalDateTime.now();
    }

    public void cancel() {
        status = KfePaymentRequestStatus.CANCELLED;
        cancelledAt = LocalDateTime.now();
    }

    public void markPaid(UUID transactionId) {
        status = KfePaymentRequestStatus.PAID;
        paidTransactionId = transactionId;
    }

    public UUID getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    public UUID getAddressId() {
        return addressId;
    }

    public void setAddressId(UUID addressId) {
        this.addressId = addressId;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public KfeRail getRail() {
        return rail;
    }

    public void setRail(KfeRail rail) {
        this.rail = rail;
    }

    public KfePaymentRequestStatus getStatus() {
        return status;
    }

    public void setStatus(KfePaymentRequestStatus status) {
        this.status = status;
    }

    public Long getAmountSats() {
        return amountSats;
    }

    public void setAmountSats(Long amountSats) {
        this.amountSats = amountSats;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public String getPayerHint() {
        return payerHint;
    }

    public void setPayerHint(String payerHint) {
        this.payerHint = payerHint;
    }

    public UUID getPaidTransactionId() {
        return paidTransactionId;
    }

    public void setPaidTransactionId(UUID paidTransactionId) {
        this.paidTransactionId = paidTransactionId;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getHiddenAt() {
        return hiddenAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }
}
