package com.kerosene.kfe.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.integration.KfeRemoteStompRelayClient;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

@Service
public class KfeDashboardPublisher {

    public static final String DESTINATION = "/queue/kfe-dashboard";

    private final SimpMessagingTemplate messagingTemplate;
    private final KfeDashboardService dashboardService;
    private final KfeRemoteStompRelayClient remoteRelay;
    private final Executor executor;
    private KfeMaintenanceGuard maintenanceGuard = KfeMaintenanceGuard.unavailable();

    @Autowired
    public KfeDashboardPublisher(
            ObjectProvider<SimpMessagingTemplate> messagingTemplate,
            KfeDashboardService dashboardService,
            ObjectProvider<KfeRemoteStompRelayClient> remoteRelay) {
        this(messagingTemplate, dashboardService, remoteRelay, ForkJoinPool.commonPool());
    }

    KfeDashboardPublisher(
            ObjectProvider<SimpMessagingTemplate> messagingTemplate,
            KfeDashboardService dashboardService,
            ObjectProvider<KfeRemoteStompRelayClient> remoteRelay,
            Executor executor) {
        this.messagingTemplate = messagingTemplate.getIfAvailable();
        this.dashboardService = dashboardService;
        this.remoteRelay = remoteRelay.getIfAvailable();
        this.executor = Objects.requireNonNull(executor);
    }

    @Autowired
    public void setMaintenanceGuard(KfeMaintenanceGuard guard) {
        this.maintenanceGuard = Objects.requireNonNull(guard);
    }

    public void publishAfterCommit(Long userId) {
        if (userId == null || (messagingTemplate == null && remoteRelay == null)) {
            return;
        }
        Runnable publish = () -> {
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSendToUser(
                        String.valueOf(userId),
                        DESTINATION,
                        dashboardService.dashboard(userId));
                return;
            }
            // Standalone KFE → Core: never POST the full dashboard (hits Tomcat
            // 2KB form/post limit → HTTP 413). Clients already consume balance +
            // transactions queues; send a tiny dirty tick only.
            remoteRelay.publishToUser(
                    userId,
                    DESTINATION,
                    Map.of(
                            "type", "KFE_DASHBOARD_DIRTY",
                            "userId", userId));
        };
        maintenanceGuard.executeMutation("publisher.dashboard.enqueue", () -> {
            maintenanceGuard.scheduleContinuation("publisher.dashboard.delivery", executor, () ->
                    maintenanceGuard.executeMutation("publisher.dashboard.delivery", () -> {
                        publish.run();
                        return Boolean.TRUE;
                    }, ignored -> false)); // Transport return is not recipient acknowledgement.
            return Boolean.TRUE;
        });
    }
}
