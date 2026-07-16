package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;

@Service
public class BalanceEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BalanceEventPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectProvider<KfeBalanceMetrics> balanceMetrics;

    public BalanceEventPublisher(
            ObjectProvider<SimpMessagingTemplate> messagingTemplate,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics) {
        this.messagingTemplate = messagingTemplate.getIfAvailable();
        this.balanceMetrics = balanceMetrics;
    }

    /** Legacy scalar publish — prefer {@link #publishBalanceUpdateAfterCommit(BalanceUpdateEvent)}. */
    public void publishBalanceUpdateAfterCommit(Long userId, String walletId, String walletName,
            BigDecimal newBalance, BigDecimal amount, String context) {
        publishBalanceUpdateAfterCommit(new BalanceUpdateEvent(
                walletId, walletName, userId, newBalance, amount, context));
    }

    public void publishBalanceUpdateAfterCommit(BalanceUpdateEvent event) {
        if (messagingTemplate == null || event == null || event.getUserId() == null) {
            return;
        }
        Runnable publish = () -> {
            try {
                messagingTemplate.convertAndSendToUser(
                        String.valueOf(event.getUserId()),
                        "/queue/balance",
                        event);
                KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
                if (metrics != null) {
                    metrics.recordWsPublish(event.getBucket());
                }
                log.info(
                        "[WS] Published balance update to user {} /queue/balance - Wallet: {}, kind={}, bucket={}, primarySats={}, NewBalance: {}, Amount: {}",
                        event.getUserId(),
                        event.getWalletName(),
                        event.getKind(),
                        event.getBucket(),
                        event.getPrimarySats(),
                        event.getNewBalance(),
                        event.getAmount());
            } catch (Exception e) {
                log.error(
                        "Failed to convert or send balance update websocket event to user {}",
                        event.getUserId(),
                        e);
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
