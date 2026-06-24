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

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", length = 255)
    private String failureMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
