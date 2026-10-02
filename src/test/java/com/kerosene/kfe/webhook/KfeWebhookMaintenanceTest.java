package com.kerosene.kfe.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.maintenance.*;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeWebhookMaintenanceTest {
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final RestTemplate remote = mock(RestTemplate.class);
    private final KfeMaintenanceStore.Admission parent = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final KfeMaintenanceStore.Admission child = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private KfeWebhookDeliveryService service;

    @BeforeEach
    void setup() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.setConnectTimeout(any())).thenReturn(builder);
        when(builder.setReadTimeout(any())).thenReturn(builder);
        when(builder.build()).thenReturn(remote);
        KfeWebhookConfig config = new KfeWebhookConfig();
        config.setSigningSecret("synthetic-unit-test-webhook-secret");
        config.setMaxRetries(0);
        service = new KfeWebhookDeliveryService(new ObjectMapper(), builder, config);
        service.setMaintenanceGuard(new KfeMaintenanceService(store));
        when(store.admit("webhook.enqueue")).thenReturn(parent);
        when(store.captureContinuation(parent.id(), "webhook.delivery", true)).thenReturn(child);
        when(store.claimContinuation(child.id())).thenReturn(child);
    }

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void drainRejectionCannotScheduleOrDeliver() {
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
        assertThatThrownBy(() -> publish()).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verify(store, never()).captureContinuation(any(), anyString(), anyBoolean());
        verifyNoInteractions(remote);
    }

    @Test
    void rollbackCancelsUnstartedDeliveryRatherThanSendingIt() {
        bindTransaction();
        publish();
        verify(store).captureContinuation(parent.id(), "webhook.delivery", true);
        verifyNoInteractions(remote);
        finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(store).releaseContinuation(child.id(), false);
        verifyNoInteractions(remote);
    }

    @Test
    void commitDoesNotCompleteUntilActualAsyncDeliveryAndFailuresStayUncertain() throws Exception {
        CountDownLatch resolved = new CountDownLatch(1);
        doAnswer(invocation -> { resolved.countDown(); return null; })
                .when(store).resolve(eq(child.id()), anyBoolean());
        when(remote.postForEntity(anyString(), any(), eq(Void.class))).thenThrow(new IllegalStateException("synthetic remote timeout"));
        bindTransaction();
        publish();
        verify(store, never()).resolve(eq(child.id()), anyBoolean());
        verifyNoInteractions(remote);
        finish(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(resolved.await(5, TimeUnit.SECONDS)).isTrue();
        verify(store).releaseContinuation(child.id(), true);
        verify(store).claimContinuation(child.id());
        verify(store).resolve(child.id(), false);
        verify(store, never()).resolve(child.id(), true);
    }

    private void publish() {
        // Null timestamp avoids needing unrelated Jackson time configuration.
        service.publishAfterCommit("https://synthetic.example.invalid/webhook",
                new KfeWebhookPayload(UUID.randomUUID(), KfeWebhookEvent.PAYMENT_RECEIVED,
                        null, "synthetic-public-id", 100L, "PAID", null));
    }

    private void bindTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void finish(int status) {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        callbacks.forEach(callback -> callback.afterCompletion(status));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
