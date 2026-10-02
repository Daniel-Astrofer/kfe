package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.integration.KfeRemoteStompRelayClient;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Pushes statement/extrato rows to the authenticated user after commit.
 *
 * <p>Destination: {@code /user/queue/transactions}. Payload is the same display
 * map persisted in {@code user_statement_24h} ({@code buildDisplayPayload}).
 *
 * <p>In KFE standalone (no in-process broker), publishes via HTTP relay to Core.
 */
@Service
public class TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);
    public static final String DESTINATION = "/queue/transactions";

    private final SimpMessagingTemplate messagingTemplate;
    private final KfeRemoteStompRelayClient remoteRelay;
    private final Executor executor;
    private KfeMaintenanceGuard maintenanceGuard = KfeMaintenanceGuard.unavailable();

    @Autowired
    public TransactionEventPublisher(
            ObjectProvider<SimpMessagingTemplate> messagingTemplate,
            ObjectProvider<KfeRemoteStompRelayClient> remoteRelay) {
        this(messagingTemplate, remoteRelay, ForkJoinPool.commonPool());
    }

    TransactionEventPublisher(
            ObjectProvider<SimpMessagingTemplate> messagingTemplate,
            ObjectProvider<KfeRemoteStompRelayClient> remoteRelay,
            Executor executor) {
        this.messagingTemplate = messagingTemplate.getIfAvailable();
        this.remoteRelay = remoteRelay.getIfAvailable();
        this.executor = Objects.requireNonNull(executor);
    }

    @Autowired
    public void setMaintenanceGuard(KfeMaintenanceGuard guard) {
        this.maintenanceGuard = Objects.requireNonNull(guard);
    }

    public void publishAfterCommit(Long userId, Map<String, ?> payload) {
        if (userId == null || payload == null || payload.isEmpty()) {
            return;
        }
        if (messagingTemplate == null && remoteRelay == null) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>(payload);
        Runnable publish = () -> {
            try {
                if (messagingTemplate != null) {
                    messagingTemplate.convertAndSendToUser(
                            String.valueOf(userId),
                            DESTINATION,
                            body);
                } else {
                    remoteRelay.publishToUser(userId, DESTINATION, body);
                }
                log.info(
                        "[WS] Published transaction to user {} {} id={}",
                        userId,
                        DESTINATION,
                        body.getOrDefault("transactionId", body.get("id")));
            } catch (Exception e) {
                log.error(
                        "Failed to send transaction websocket event to user {}",
                        userId,
                        e);
            }
        };

        maintenanceGuard.executeMutation("publisher.transaction.enqueue", () -> {
            maintenanceGuard.scheduleContinuation("publisher.transaction.delivery", executor, () ->
                    maintenanceGuard.executeMutation("publisher.transaction.delivery", () -> {
                        publish.run();
                        return Boolean.TRUE;
                    }, ignored -> false)); // Includes caught and best-effort relay failures.
            return Boolean.TRUE;
        });
    }
}
