package com.kerosene.kfe.dto;

import java.math.BigDecimal;
import java.time.Instant;

public class KfeFeeQuoteResponse {

    private String quoteId;
    private String userId;
    private String walletId;
    private String destinationHash;
    private String rail;
    private BigDecimal amount;
    private Long networkFeeSat;
    private Long serviceFeeSat;
    private Long totalDebitSat;
    private int pricingPolicyVersion;
    private int feeEstimateVersion;
    private Instant expiresAt;
    private String signature;

    public String getQuoteId() {
        return quoteId;
    }

    public void setQuoteId(String quoteId) {
        this.quoteId = quoteId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getWalletId() {
        return walletId;
    }

    public void setWalletId(String walletId) {
        this.walletId = walletId;
    }

    public String getDestinationHash() {
        return destinationHash;
    }

    public void setDestinationHash(String destinationHash) {
        this.destinationHash = destinationHash;
    }

    public String getRail() {
        return rail;
    }

    public void setRail(String rail) {
        this.rail = rail;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Long getNetworkFeeSat() {
        return networkFeeSat;
    }

    public void setNetworkFeeSat(Long networkFeeSat) {
        this.networkFeeSat = networkFeeSat;
    }

    public Long getServiceFeeSat() {
        return serviceFeeSat;
    }

    public void setServiceFeeSat(Long serviceFeeSat) {
        this.serviceFeeSat = serviceFeeSat;
    }

    public Long getTotalDebitSat() {
        return totalDebitSat;
    }

    public void setTotalDebitSat(Long totalDebitSat) {
        this.totalDebitSat = totalDebitSat;
    }

    public int getPricingPolicyVersion() {
        return pricingPolicyVersion;
    }

    public void setPricingPolicyVersion(int pricingPolicyVersion) {
        this.pricingPolicyVersion = pricingPolicyVersion;
    }

    public int getFeeEstimateVersion() {
        return feeEstimateVersion;
    }

    public void setFeeEstimateVersion(int feeEstimateVersion) {
        this.feeEstimateVersion = feeEstimateVersion;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }
}
