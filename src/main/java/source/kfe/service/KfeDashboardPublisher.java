package source.kfe.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class KfeDashboardPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final KfeDashboardService dashboardService;

    public KfeDashboardPublisher(
            ObjectProvider<SimpMessagingTemplate> messagingTemplate,
            KfeDashboardService dashboardService) {
        this.messagingTemplate = messagingTemplate.getIfAvailable();
        this.dashboardService = dashboardService;
    }

    public void publishAfterCommit(Long userId) {
        if (messagingTemplate == null) {
            return;
        }
        Runnable publish = () -> messagingTemplate.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/kfe-dashboard",
                dashboardService.dashboard(userId));
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
