package source.kfe.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class BalanceUpdateEvent {
    private String walletId;
    private String walletName;
    private Long userId;
    private BigDecimal newBalance;
    private BigDecimal amount;
    private String context;
    private LocalDateTime timestamp;

    public BalanceUpdateEvent(String walletId, String walletName, Long userId, BigDecimal newBalance,
            BigDecimal amount, String context) {
        this.walletId = walletId;
        this.walletName = walletName;
        this.userId = userId;
        this.newBalance = newBalance;
        this.amount = amount;
        this.context = context;
        this.timestamp = LocalDateTime.now();
    }

    public String getWalletId() {
        return walletId;
    }

    public void setWalletId(String walletId) {
        this.walletId = walletId;
    }

    public String getWalletName() {
        return walletName;
    }

    public void setWalletName(String walletName) {
        this.walletName = walletName;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getNewBalance() {
        return newBalance;
    }

    public void setNewBalance(BigDecimal newBalance) {
        this.newBalance = newBalance;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
