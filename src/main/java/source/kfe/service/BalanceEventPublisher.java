package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;

@Service
public class BalanceEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BalanceEventPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;

    public BalanceEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publishBalanceUpdateAfterCommit(Long userId, String walletId, String walletName,
            BigDecimal newBalance, BigDecimal amount, String context) {
        Runnable publish = () -> {
            try {
                BalanceUpdateEvent event = new BalanceUpdateEvent(
                        walletId, walletName, userId, newBalance, amount, context);

                messagingTemplate.convertAndSendToUser(
                        String.valueOf(userId),
                        "/queue/balance",
                        event);
                log.info("[WS] Published balance update to user {} /queue/balance - Wallet: {}, NewBalance: {}, Amount: {}",
                        userId,
                        walletName,
                        newBalance,
                        amount);
            } catch (Exception e) {
                log.error("Failed to convert or send balance update websocket event to user {}", userId, e);
            }
        };

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
    }
}
