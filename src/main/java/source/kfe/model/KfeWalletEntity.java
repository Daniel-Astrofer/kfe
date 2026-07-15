package source.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import source.common.persistence.StringCryptoConverter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "wallets_core", schema = "financial", indexes = {
        @Index(name = "idx_wallets_core_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_wallets_core_kind_status", columnList = "kind, status")
})
public class KfeWalletEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private KfeWalletKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfeWalletStatus status = KfeWalletStatus.CREATING;

    @Column(name = "label", nullable = false, length = 96)
    private String label;

    @Column(name = "asset", nullable = false, length = 16)
    private String asset = "BTC";

    @Column(name = "spendable", nullable = false)
    private boolean spendable = true;

    @Column(name = "mpc_public_key", columnDefinition = "TEXT")
    private String mpcPublicKey;

    @Convert(converter = StringCryptoConverter.class)
    @Column(name = "xpub", columnDefinition = "TEXT")
    private String xpub;

    @Convert(converter = StringCryptoConverter.class)
    @Column(name = "descriptor", columnDefinition = "TEXT")
    private String descriptor;

    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    @Column(name = "derivation_path", length = 160)
    private String derivationPath;

    @Column(name = "last_derived_index", nullable = false)
    private int lastDerivedIndex = -1;

    @Column(name = "quorum_policy_hash", nullable = false, length = 64)
    private String quorumPolicyHash;

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

    public void setId(UUID id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public KfeWalletKind getKind() {
        return kind;
    }

    public void setKind(KfeWalletKind kind) {
        this.kind = kind;
    }

    public KfeWalletStatus getStatus() {
        return status;
    }

    public void setStatus(KfeWalletStatus status) {
        this.status = status;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getAsset() {
        return asset;
    }

    public void setAsset(String asset) {
        this.asset = asset;
    }

    public boolean isSpendable() {
        return spendable;
    }

    public void setSpendable(boolean spendable) {
        this.spendable = spendable;
    }

    public String getMpcPublicKey() {
        return mpcPublicKey;
    }

    public void setMpcPublicKey(String mpcPublicKey) {
        this.mpcPublicKey = mpcPublicKey;
    }

    public String getXpub() {
        return xpub;
    }

    public void setXpub(String xpub) {
        this.xpub = xpub;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public void setDescriptor(String descriptor) {
        this.descriptor = descriptor;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getDerivationPath() {
        return derivationPath;
    }

    public void setDerivationPath(String derivationPath) {
        this.derivationPath = derivationPath;
    }

    public int getLastDerivedIndex() {
        return lastDerivedIndex;
    }

    public void setLastDerivedIndex(int lastDerivedIndex) {
        this.lastDerivedIndex = lastDerivedIndex;
    }

    public String getQuorumPolicyHash() {
        return quorumPolicyHash;
    }

    public void setQuorumPolicyHash(String quorumPolicyHash) {
        this.quorumPolicyHash = quorumPolicyHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
