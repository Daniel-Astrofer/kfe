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
@Table(name = "kfe_psbt_workflows", schema = "financial", indexes = {
        @Index(name = "idx_psbt_workflows_wallet_created", columnList = "wallet_id, created_at"),
        @Index(name = "idx_psbt_workflows_status", columnList = "status")
})
public class KfePsbtWorkflowEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfePsbtWorkflowStatus status = KfePsbtWorkflowStatus.CREATED;

    @Column(name = "psbt", nullable = false, columnDefinition = "TEXT")
    private String psbt;

    @Column(name = "signed_psbt", columnDefinition = "TEXT")
    private String signedPsbt;

    @Column(name = "raw_tx_hex", columnDefinition = "TEXT")
    private String rawTxHex;

    @Column(name = "psbt_hash", nullable = false, length = 64)
    private String psbtHash;

    @Column(name = "signed_psbt_hash", length = 64)
    private String signedPsbtHash;

    @Column(name = "raw_tx_hash", length = 64)
    private String rawTxHash;

    @Column(name = "broadcast_txid", length = 128)
    private String broadcastTxid;

    @Column(name = "amount_sats", nullable = false)
    private long amountSats;

    @Column(name = "fee_sats", nullable = false)
    private long feeSats;

    @Column(name = "destination_address", nullable = false, length = 128)
    private String destinationAddress;

    @Column(name = "inputs_json", columnDefinition = "TEXT")
    private String inputsJson;

    @Column(name = "failure_message", length = 255)
    private String failureMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "broadcast_at")
    private LocalDateTime broadcastAt;

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

    public UUID getId() {
        return id;
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

    public KfePsbtWorkflowStatus getStatus() {
        return status;
    }

    public void setStatus(KfePsbtWorkflowStatus status) {
        this.status = status;
    }

    public String getPsbt() {
        return psbt;
    }

    public void setPsbt(String psbt) {
        this.psbt = psbt;
    }

    public String getSignedPsbt() {
        return signedPsbt;
    }

    public void setSignedPsbt(String signedPsbt) {
        this.signedPsbt = signedPsbt;
    }

    public String getRawTxHex() {
        return rawTxHex;
    }

    public void setRawTxHex(String rawTxHex) {
        this.rawTxHex = rawTxHex;
    }

    public String getPsbtHash() {
        return psbtHash;
    }

    public void setPsbtHash(String psbtHash) {
        this.psbtHash = psbtHash;
    }

    public String getSignedPsbtHash() {
        return signedPsbtHash;
    }

    public void setSignedPsbtHash(String signedPsbtHash) {
        this.signedPsbtHash = signedPsbtHash;
    }

    public String getRawTxHash() {
        return rawTxHash;
    }

    public void setRawTxHash(String rawTxHash) {
        this.rawTxHash = rawTxHash;
    }

    public String getBroadcastTxid() {
        return broadcastTxid;
    }

    public void setBroadcastTxid(String broadcastTxid) {
        this.broadcastTxid = broadcastTxid;
    }

    public long getAmountSats() {
        return amountSats;
    }

    public void setAmountSats(long amountSats) {
        this.amountSats = amountSats;
    }

    public long getFeeSats() {
        return feeSats;
    }

    public void setFeeSats(long feeSats) {
        this.feeSats = feeSats;
    }

    public String getDestinationAddress() {
        return destinationAddress;
    }

    public void setDestinationAddress(String destinationAddress) {
        this.destinationAddress = destinationAddress;
    }

    public String getInputsJson() {
        return inputsJson;
    }

    public void setInputsJson(String inputsJson) {
        this.inputsJson = inputsJson;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(LocalDateTime signedAt) {
        this.signedAt = signedAt;
    }

    public LocalDateTime getBroadcastAt() {
        return broadcastAt;
    }

    public void setBroadcastAt(LocalDateTime broadcastAt) {
        this.broadcastAt = broadcastAt;
    }
}
