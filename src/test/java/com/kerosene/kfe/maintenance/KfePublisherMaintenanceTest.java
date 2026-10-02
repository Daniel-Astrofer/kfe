package com.kerosene.kfe.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kerosene.kfe.dto.KfeDashboardResponse;
import com.kerosene.kfe.integration.KfeRemoteStompRelayClient;
import com.kerosene.kfe.service.BalanceEventPublisher;
import com.kerosene.kfe.service.BalanceUpdateEvent;
import com.kerosene.kfe.service.KfeBalanceMetrics;
import com.kerosene.kfe.service.KfeDashboardPublisher;
import com.kerosene.kfe.service.KfeDashboardService;
import com.kerosene.kfe.service.TransactionEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfePublisherMaintenanceTest {
    private enum Publisher {
        DASHBOARD("dashboard", KfeDashboardPublisher.DESTINATION),
        BALANCE("balance", BalanceEventPublisher.DESTINATION),
        TRANSACTION("transaction", TransactionEventPublisher.DESTINATION);

        final String operation;
        final String destination;

        Publisher(String name, String destination) {
            this.operation = "publisher." + name;
            this.destination = destination;
        }
    }

    private static final Long USER = 42L;
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission parent =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 4);
    private final KfeMaintenanceStore.Admission child =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 4);
    private final ControlledExecutor executor = new ControlledExecutor();
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final KfeRemoteStompRelayClient relay = mock(KfeRemoteStompRelayClient.class);
    private final KfeDashboardService dashboard = mock(KfeDashboardService.class);
    private final KfeDashboardResponse dashboardBody = mock(KfeDashboardResponse.class);
    private final KfeBalanceMetrics metrics = mock(KfeBalanceMetrics.class);
    private final BalanceUpdateEvent balanceBody = new BalanceUpdateEvent(
            "wallet-1", "Wallet", USER, BigDecimal.TEN, BigDecimal.ONE, "test",
            "INTERNAL", 1000L, 0L, 0L, 0L, 1000L, "AVAILABLE");
    private final Map<String, Object> transactionBody =
            new LinkedHashMap<>(Map.of("transactionId", "tx-1", "amount", 10));

    @BeforeEach
    void activeStore() {
        when(store.admit(anyString())).thenReturn(parent);
        when(store.captureContinuation(eq(parent.id()), anyString(), anyBoolean())).thenReturn(child);
        when(store.claimContinuation(child.id())).thenReturn(child);
        when(dashboard.dashboard(USER)).thenReturn(dashboardBody);
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    static Stream<Arguments> publishersAndTransports() {
        return Stream.of(Publisher.values()).flatMap(publisher ->
                Stream.of(Arguments.of(publisher, true), Arguments.of(publisher, false)));
    }

    @ParameterizedTest
    @MethodSource("publishersAndTransports")
    void commitReleasesReadyChildBeforeLaterClaimAndTransactionFreeEffect(Publisher publisher, boolean local)
            throws Exception {
        Runnable publish = configured(publisher, local, executor, true);
        bindTransaction();
        publish.run();
        verify(store).admit(publisher.operation + ".enqueue");
        verify(store).captureContinuation(parent.id(), publisher.operation + ".delivery", true);
        assertThat(executor.queued).isEmpty();
        verifyNoInteractions(messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());
        verify(store, never()).resolve(any(), anyBoolean());

        // Deliberately leave the caller's completed transaction bound: the worker must not inherit it.
        complete(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(executor.queued).hasSize(1);
        verify(store).releaseContinuation(child.id(), true); // WAITING -> READY
        verify(store).resolve(parent.id(), true);
        verify(store, never()).claimContinuation(any());
        verify(store, never()).resolve(eq(child.id()), anyBoolean());
        verifyNoInteractions(messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());

        Object expectedBody = body(publisher, local);
        if (local) {
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                return null;
            }).when(messaging).convertAndSendToUser("42", publisher.destination, expectedBody);
        } else {
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                return null;
            }).when(relay).publishToUser(USER, publisher.destination, expectedBody);
        }
        assertThat(executor.runNext()).isNull();
        var order = inOrder(store, messaging, relay);
        order.verify(store).admit(publisher.operation + ".enqueue");
        order.verify(store).captureContinuation(parent.id(), publisher.operation + ".delivery", true);
        order.verify(store).resolve(parent.id(), true);
        order.verify(store).releaseContinuation(child.id(), true);
        order.verify(store).claimContinuation(child.id());
        if (local) {
            order.verify(messaging).convertAndSendToUser("42", publisher.destination, expectedBody);
            verifyNoInteractions(relay);
        } else {
            order.verify(relay).publishToUser(USER, publisher.destination, expectedBody);
            verifyNoInteractions(messaging);
        }
        order.verify(store).resolve(child.id(), false);
        verify(store, times(1)).admit(anyString()); // Nested delivery shares the claimed child.
        verify(store, never()).resolve(child.id(), true);
        if (publisher == Publisher.BALANCE) {
            verify(metrics).recordWsPublish("AVAILABLE");
        }
        if (publisher == Publisher.DASHBOARD && !local) {
            verify(dashboard, never()).dashboard(any()); // Remote path keeps the tiny dirty tick.
        }
    }

    @ParameterizedTest
    @MethodSource("publishersAndTransports")
    void rollbackCancelsChildWithoutClaimOrPublish(Publisher publisher, boolean local) throws Exception {
        Runnable publish = configured(publisher, local, executor, true);
        bindTransaction();
        publish.run();
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(store).releaseContinuation(child.id(), false);
        verify(store).resolve(parent.id(), false);
        verify(store, never()).claimContinuation(any());
        verify(store, never()).resolve(eq(child.id()), anyBoolean());
        assertThat(executor.queued).isEmpty();
        verifyNoInteractions(messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());
    }

    @ParameterizedTest
    @EnumSource(Publisher.class)
    void unknownTransactionCompletionLeavesChildWaiting(Publisher publisher) throws Exception {
        Runnable publish = configured(publisher, true, executor, true);
        bindTransaction();
        publish.run();
        complete(TransactionSynchronization.STATUS_UNKNOWN);
        verify(store, never()).releaseContinuation(any(), anyBoolean());
        verify(store, never()).claimContinuation(any());
        verify(store).resolve(parent.id(), false);
        assertThat(executor.queued).isEmpty();
        verifyNoInteractions(messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());
    }

    @ParameterizedTest
    @MethodSource("publishersAndTransports")
    void withoutTransactionCaptureStillPrecedesAsyncEffect(Publisher publisher, boolean local) throws Exception {
        configured(publisher, local, executor, true).run();
        verify(store).captureContinuation(parent.id(), publisher.operation + ".delivery", false);
        verify(store).resolve(parent.id(), true);
        verify(store, never()).releaseContinuation(any(), anyBoolean());
        verify(store, never()).claimContinuation(any());
        verify(store, never()).resolve(eq(child.id()), anyBoolean());
        verifyNoInteractions(messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());
        assertThat(executor.runNext()).isNull();
        verifyDelivery(publisher, local);
        verify(store).resolve(child.id(), false);
    }

    @ParameterizedTest
    @EnumSource(Publisher.class)
    void drainRejectsNewRootButAdmittedChildCanFinish(Publisher publisher) throws Exception {
        Runnable publish = configured(publisher, true, executor, true);
        bindTransaction();
        publish.run();
        var command = new KfeMaintenanceGuard.Command("cell-update", "maintenance", 4);
        when(store.transition(KfeMaintenanceGuard.Action.DRAIN, command, USER)).thenReturn(
                new KfeMaintenanceStore.Control(KfeMaintenanceGuard.Mode.DRAINING, "cell-update", 5));
        guard.requestDrain(command, USER);
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
        assertThatThrownBy(publish::run).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verify(store, times(1)).captureContinuation(any(), anyString(), anyBoolean());
        complete(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(executor.runNext()).isNull();
        verifyDelivery(publisher, true);
        verify(store, times(2)).admit(anyString()); // Rejected new root; child did not re-admit.
        verify(store).resolve(child.id(), false);
    }

    @ParameterizedTest
    @EnumSource(Publisher.class)
    void nestedPublisherSharesAlreadyAdmittedParentEvenAfterDrain(Publisher publisher) throws Exception {
        Runnable publish = configured(publisher, false, executor, true);
        guard.executeMutation("financial.parent", () -> {
            when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
            publish.run();
            return true;
        });
        verify(store, times(1)).admit("financial.parent");
        verify(store).captureContinuation(parent.id(), publisher.operation + ".delivery", false);
        assertThat(executor.runNext()).isNull();
        verifyDelivery(publisher, false);
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(child.id(), false);
    }

    @ParameterizedTest
    @MethodSource("publishersAndTransports")
    void unavailableGuardRejectsBeforeScheduling(Publisher publisher, boolean local) throws Exception {
        Runnable publish = configured(publisher, local, executor, false);
        assertThatThrownBy(publish::run).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        assertThat(executor.queued).isEmpty();
        verifyNoInteractions(store, messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());
    }

    @ParameterizedTest
    @EnumSource(Publisher.class)
    void admissionStorageFailurePreventsCaptureAndPublish(Publisher publisher) throws Exception {
        Runnable publish = configured(publisher, true, executor, true);
        when(store.admit(anyString())).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(publish::run).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verify(store, never()).captureContinuation(any(), anyString(), anyBoolean());
        assertThat(executor.queued).isEmpty();
        verifyNoInteractions(messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());
    }

    @ParameterizedTest
    @EnumSource(Publisher.class)
    void childPersistenceFailureLeavesParentUncertainWithoutEnqueue(Publisher publisher) throws Exception {
        Runnable publish = configured(publisher, true, executor, true);
        when(store.captureContinuation(any(), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(publish::run).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verify(store).resolve(parent.id(), false);
        assertThat(executor.queued).isEmpty();
        verifyNoInteractions(messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());
    }

    @ParameterizedTest
    @EnumSource(Publisher.class)
    void transactionWithoutCompletionObservationRejectsBeforeCapture(Publisher publisher) throws Exception {
        Runnable publish = configured(publisher, true, executor, true);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(publish::run).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verify(store, never()).captureContinuation(any(), anyString(), anyBoolean());
        assertThat(executor.queued).isEmpty();
        verifyNoInteractions(messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());
    }

    @ParameterizedTest
    @EnumSource(Publisher.class)
    void failedCommitReleaseDoesNotEnqueueOrCompleteChild(Publisher publisher) throws Exception {
        Runnable publish = configured(publisher, true, executor, true);
        bindTransaction();
        publish.run();
        doThrow(new IllegalStateException("release storage unavailable"))
                .when(store).releaseContinuation(child.id(), true);
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(parent.id(), true);
        verify(store, never()).claimContinuation(any());
        verify(store, never()).resolve(eq(child.id()), anyBoolean());
        assertThat(executor.queued).isEmpty();
        verifyNoInteractions(messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());
    }

    @ParameterizedTest
    @EnumSource(Publisher.class)
    void rejectedChildClaimPreventsEveryDeliveryEffect(Publisher publisher) throws Exception {
        configured(publisher, true, executor, true).run();
        when(store.claimContinuation(child.id())).thenThrow(
                new KfeMaintenanceGuard.MaintenanceException(409, "already claimed"));
        assertThat(executor.runNext()).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verify(store, never()).resolve(eq(child.id()), anyBoolean());
        verifyNoInteractions(messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());
    }

    @ParameterizedTest
    @EnumSource(Publisher.class)
    void executorRejectionKeepsReadyChildUnresolved(Publisher publisher) throws Exception {
        Executor rejecting = work -> { throw new RejectedExecutionException("stopped"); };
        configured(publisher, true, rejecting, true).run();
        verify(store).captureContinuation(parent.id(), publisher.operation + ".delivery", false);
        verify(store, never()).claimContinuation(any());
        verify(store, never()).resolve(eq(child.id()), anyBoolean());
        verifyNoInteractions(messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());
    }

    @ParameterizedTest
    @MethodSource("publishersAndTransports")
    void thrownOrPublisherCaughtTransportFailureRemainsUncertain(Publisher publisher, boolean local)
            throws Exception {
        if (local) {
            doThrow(new IllegalStateException("send failed"))
                    .when(messaging).convertAndSendToUser(eq("42"), eq(publisher.destination), any());
        } else {
            doThrow(new IllegalStateException("relay failed"))
                    .when(relay).publishToUser(eq(USER), eq(publisher.destination), any());
        }
        configured(publisher, local, executor, true).run();
        Throwable failure = executor.runNext();
        if (publisher == Publisher.DASHBOARD) {
            assertThat(failure).isInstanceOf(IllegalStateException.class);
        } else {
            assertThat(failure).isNull(); // Existing balance/transaction catches remain in place.
        }
        verify(store).resolve(child.id(), false);
        verify(store, never()).resolve(child.id(), true);
        verifyNoInteractions(metrics);
    }

    @ParameterizedTest
    @EnumSource(Publisher.class)
    void actualBestEffortRelaySwallowedHttpFailureCannotCompleteChild(Publisher publisher) throws Exception {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        RestTemplate http = mock(RestTemplate.class);
        when(builder.setConnectTimeout(any())).thenReturn(builder);
        when(builder.setReadTimeout(any())).thenReturn(builder);
        when(builder.build()).thenReturn(http);
        when(http.postForEntity(anyString(), any(), eq(Void.class)))
                .thenThrow(new IllegalStateException("remote timeout"));
        KfeRemoteStompRelayClient bestEffort = new KfeRemoteStompRelayClient(
                builder, new ObjectMapper().registerModule(new JavaTimeModule()),
                "http://relay.invalid", "test-only-secret", 10, 10);
        Object instance = construct(publisher, null, bestEffort, executor);
        injectGuard(instance);
        invokePublish(instance, publisher, USER);
        assertThat(executor.runNext()).isNull();
        verify(http).postForEntity(eq("http://relay.invalid/internal/kfe/stomp/publish"), any(), eq(Void.class));
        verify(store).resolve(child.id(), false);
        verify(store, never()).resolve(child.id(), true);
    }

    @ParameterizedTest
    @EnumSource(Publisher.class)
    void userAndNoTransportNoopsDoNotNeedAdmission(Publisher publisher) throws Exception {
        Object withTransport = construct(publisher, messaging, relay, executor);
        invokePublish(withTransport, publisher, null);
        Object withoutTransport = construct(publisher, null, null, executor);
        invokePublish(withoutTransport, publisher, USER);
        assertThat(executor.queued).isEmpty();
        verifyNoInteractions(store, messaging, relay, metrics);
        verify(dashboard, never()).dashboard(any());
    }

    @Test
    void nullAndEmptyEventNoopsDoNotNeedAdmission() throws Exception {
        BalanceEventPublisher balance = (BalanceEventPublisher) construct(Publisher.BALANCE, messaging, relay, executor);
        balance.publishBalanceUpdateAfterCommit((BalanceUpdateEvent) null);
        TransactionEventPublisher transaction = (TransactionEventPublisher) construct(
                Publisher.TRANSACTION, messaging, relay, executor);
        transaction.publishAfterCommit(USER, null);
        transaction.publishAfterCommit(USER, Map.of());
        assertThat(executor.queued).isEmpty();
        verifyNoInteractions(store, messaging, relay, metrics);
    }

    @Test
    void transactionPayloadCopySurvivesCallerMutation() throws Exception {
        Runnable publish = configured(Publisher.TRANSACTION, true, executor, true);
        Map<String, Object> expected = new LinkedHashMap<>(transactionBody);
        publish.run();
        transactionBody.clear();
        assertThat(executor.runNext()).isNull();
        verify(messaging).convertAndSendToUser("42", TransactionEventPublisher.DESTINATION, expected);
        verify(store).resolve(child.id(), false);
    }

    @Test
    void legacyBalanceOverloadStillCapturesDurableChild() throws Exception {
        BalanceEventPublisher publisher = (BalanceEventPublisher) construct(Publisher.BALANCE, null, relay, executor);
        publisher.setMaintenanceGuard(guard);
        publisher.publishBalanceUpdateAfterCommit(USER, "wallet-1", "Wallet", BigDecimal.TEN, BigDecimal.ONE, "legacy");
        verify(store).captureContinuation(parent.id(), "publisher.balance.delivery", false);
        assertThat(executor.runNext()).isNull();
        var payload = org.mockito.ArgumentCaptor.forClass(BalanceUpdateEvent.class);
        verify(relay).publishToUser(eq(USER), eq(BalanceEventPublisher.DESTINATION), payload.capture());
        assertThat(payload.getValue().getContext()).isEqualTo("legacy");
        assertThat(payload.getValue().getNewBalance()).isEqualTo(BigDecimal.TEN);
        verify(store).resolve(child.id(), false);
    }

    private Runnable configured(Publisher publisher, boolean local, Executor deliveryExecutor, boolean inject)
            throws Exception {
        // Supply both transports for local tests to verify broker precedence.
        Object instance = construct(publisher, local ? messaging : null, relay, deliveryExecutor);
        if (inject) {
            injectGuard(instance);
        }
        return () -> invokePublish(instance, publisher, USER);
    }

    private Object construct(Publisher publisher, SimpMessagingTemplate broker, KfeRemoteStompRelayClient remote,
            Executor deliveryExecutor) throws Exception {
        // The permitted test path has a different package; reflect only the package-private executor seam.
        return switch (publisher) {
            case DASHBOARD -> construct(KfeDashboardPublisher.class,
                    new Class<?>[]{ObjectProvider.class, KfeDashboardService.class, ObjectProvider.class, Executor.class},
                    provider(broker), dashboard, provider(remote), deliveryExecutor);
            case BALANCE -> construct(BalanceEventPublisher.class,
                    new Class<?>[]{ObjectProvider.class, ObjectProvider.class, ObjectProvider.class, Executor.class},
                    provider(broker), provider(metrics), provider(remote), deliveryExecutor);
            case TRANSACTION -> construct(TransactionEventPublisher.class,
                    new Class<?>[]{ObjectProvider.class, ObjectProvider.class, Executor.class},
                    provider(broker), provider(remote), deliveryExecutor);
        };
    }

    private static <T> T construct(Class<T> type, Class<?>[] types, Object... arguments) throws Exception {
        Constructor<T> constructor = type.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private void injectGuard(Object instance) {
        if (instance instanceof KfeDashboardPublisher publisher) {
            publisher.setMaintenanceGuard(guard);
        } else if (instance instanceof BalanceEventPublisher publisher) {
            publisher.setMaintenanceGuard(guard);
        } else if (instance instanceof TransactionEventPublisher publisher) {
            publisher.setMaintenanceGuard(guard);
        }
    }

    private void invokePublish(Object instance, Publisher publisher, Long user) {
        switch (publisher) {
            case DASHBOARD -> ((KfeDashboardPublisher) instance).publishAfterCommit(user);
            case BALANCE -> {
                balanceBody.setUserId(user);
                ((BalanceEventPublisher) instance).publishBalanceUpdateAfterCommit(balanceBody);
            }
            case TRANSACTION -> ((TransactionEventPublisher) instance).publishAfterCommit(user, transactionBody);
        }
    }

    private Object body(Publisher publisher, boolean local) {
        return switch (publisher) {
            case DASHBOARD -> local ? dashboardBody : Map.of("type", "KFE_DASHBOARD_DIRTY", "userId", USER);
            case BALANCE -> balanceBody;
            case TRANSACTION -> transactionBody;
        };
    }

    private void verifyDelivery(Publisher publisher, boolean local) {
        if (local) {
            verify(messaging).convertAndSendToUser("42", publisher.destination, body(publisher, true));
        } else {
            verify(relay).publishToUser(USER, publisher.destination, body(publisher, false));
        }
    }

    private void bindTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void complete(int status) {
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        if (status == TransactionSynchronization.STATUS_COMMITTED) {
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        }
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
    }

    private static final class ControlledExecutor implements Executor {
        final Queue<Runnable> queued = new ArrayDeque<>();

        public void execute(Runnable work) {
            queued.add(work);
        }

        Throwable runNext() throws InterruptedException {
            Runnable work = queued.remove();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    work.run();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "publisher-maintenance-test");
            worker.setDaemon(true);
            worker.start();
            worker.join(5000);
            assertThat(worker.isAlive()).as("controlled delivery finished").isFalse();
            return failure.get();
        }
    }
}
