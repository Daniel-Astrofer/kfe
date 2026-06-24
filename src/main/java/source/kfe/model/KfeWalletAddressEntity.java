package source.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "wallet_addresses", schema = "financial", indexes = {
        @Index(name = "idx_wallet_addresses_wallet_status", columnList = "wallet_id, status, created_at")
})
public class KfeWalletAddressEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "address", nullable = false, length = 128)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "address_role", nullable = false, length = 32)
    private KfeWalletAddressRole addressRole = KfeWalletAddressRole.RECEIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfeWalletAddressStatus status = KfeWalletAddressStatus.ACTIVE;

    @Column(name = "derivation_path", length = 160)
    private String derivationPath;

    @Column(name = "derivation_index")
    private Integer derivationIndex;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "first_seen_txid", length = 128)
    private String firstSeenTxid;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public void retire() {
        status = KfeWalletAddressStatus.RETIRED;
        retiredAt = LocalDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public KfeWalletAddressRole getAddressRole() {
        return addressRole;
    }

    public void setAddressRole(KfeWalletAddressRole addressRole) {
        this.addressRole = addressRole;
    }

    public KfeWalletAddressStatus getStatus() {
        return status;
    }

    public void setStatus(KfeWalletAddressStatus status) {
        this.status = status;
    }

    public String getDerivationPath() {
        return derivationPath;
    }

    public void setDerivationPath(String derivationPath) {
        this.derivationPath = derivationPath;
    }

    public Integer getDerivationIndex() {
        return derivationIndex;
    }

    public void setDerivationIndex(Integer derivationIndex) {
        this.derivationIndex = derivationIndex;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public String getFirstSeenTxid() {
        return firstSeenTxid;
    }

    public void setFirstSeenTxid(String firstSeenTxid) {
        this.firstSeenTxid = firstSeenTxid;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getRetiredAt() {
        return retiredAt;
    }
}
