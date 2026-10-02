package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.integration.KfeRemoteStompRelayClient;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

@Service
public class BalanceEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BalanceEventPublisher.class);
    public static final String DESTINATION = "/queue/balance";

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectProvider<KfeBalanceMetrics> balanceMetrics;
    private final KfeRemoteStompRelayClient remoteRelay;
    private final Executor executor;
    private KfeMaintenanceGuard maintenanceGuard = KfeMaintenanceGuard.unavailable();

    @Autowired
    public BalanceEventPublisher(
            ObjectProvider<SimpMessagingTemplate> messagingTemplate,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics,
            ObjectProvider<KfeRemoteStompRelayClient> remoteRelay) {
        this(messagingTemplate, balanceMetrics, remoteRelay, ForkJoinPool.commonPool());
    }

    BalanceEventPublisher(
            ObjectProvider<SimpMessagingTemplate> messagingTemplate,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics,
            ObjectProvider<KfeRemoteStompRelayClient> remoteRelay,
            Executor executor) {
        this.messagingTemplate = messagingTemplate.getIfAvailable();
        this.balanceMetrics = balanceMetrics;
        this.remoteRelay = remoteRelay.getIfAvailable();
        this.executor = Objects.requireNonNull(executor);
    }

    @Autowired
    public void setMaintenanceGuard(KfeMaintenanceGuard guard) {
        this.maintenanceGuard = Objects.requireNonNull(guard);
    }

    /** Legacy scalar publish — prefer {@link #publishBalanceUpdateAfterCommit(BalanceUpdateEvent)}. */
    public void publishBalanceUpdateAfterCommit(Long userId, String walletId, String walletName,
            BigDecimal newBalance, BigDecimal amount, String context) {
        publishBalanceUpdateAfterCommit(new BalanceUpdateEvent(
                walletId, walletName, userId, newBalance, amount, context));
    }

    public void publishBalanceUpdateAfterCommit(BalanceUpdateEvent event) {
        if (event == null || event.getUserId() == null) {
            return;
        }
        if (messagingTemplate == null && remoteRelay == null) {
            return;
        }
        Runnable publish = () -> {
            try {
                if (messagingTemplate != null) {
                    messagingTemplate.convertAndSendToUser(
                            String.valueOf(event.getUserId()),
                            DESTINATION,
                            event);
                } else {
                    remoteRelay.publishToUser(event.getUserId(), DESTINATION, event);
                }
                KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
                if (metrics != null) {
                    metrics.recordWsPublish(event.getBucket());
                }
                log.info(
                        "[WS] Published balance update to user {} {} - Wallet: {}, kind={}, bucket={}, primarySats={}, NewBalance: {}, Amount: {}",
                        event.getUserId(),
                        DESTINATION,
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

        maintenanceGuard.executeMutation("publisher.balance.enqueue", () -> {
            maintenanceGuard.scheduleContinuation("publisher.balance.delivery", executor, () ->
                    maintenanceGuard.executeMutation("publisher.balance.delivery", () -> {
                        publish.run();
                        return Boolean.TRUE;
                    }, ignored -> false)); // Includes caught and best-effort relay failures.
            return Boolean.TRUE;
        });
    }
}
