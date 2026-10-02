package com.kerosene.kfe.service;

import com.kerosene.kfe.maintenance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeReactiveRefreshMaintenanceTest {
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final KfeMonitoredChainAddressIndex index = mock(KfeMonitoredChainAddressIndex.class);
    private final KfeColdWalletObservationService cold = mock(KfeColdWalletObservationService.class);
    private final KfeCustodialDepositObservationService custodial = mock(KfeCustodialDepositObservationService.class);
    private final KfeOnchainBalanceSyncService sync = mock(KfeOnchainBalanceSyncService.class);
    private final ObjectProvider<KfeColdWalletObservationService> coldProvider = provider(cold);
    private final ObjectProvider<KfeCustodialDepositObservationService> custodialProvider = provider(custodial);
    private final ObjectProvider<KfeOnchainBalanceSyncService> syncProvider = provider(sync);
    private final UUID walletId = UUID.randomUUID();
    private KfeColdWalletReactiveRefreshService service;

    @BeforeEach void setup() {
        when(store.admit(anyString())).thenAnswer(call -> {
            if (draining.get()) throw new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain");
            return admission;
        });
        when(scheduler.schedule(any(Runnable.class), eq(200L), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            queued.add(call.getArgument(0)); return null;
        });
        service = new KfeColdWalletReactiveRefreshService(coldProvider, custodialProvider, syncProvider, index, 1, scheduler);
        service.setMaintenanceGuard(guard);
        when(index.allColdWalletIds()).thenReturn(Set.of(walletId));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectedDrainPreservesTargetUntilExplicitResumeOfNextIndependentTick(boolean all) {
        enqueue(all); draining.set(true);
        queued.remove().run();
        noEffects(); verify(store, never()).resolve(any(), anyBoolean());
        assertThat(queued).hasSize(1);
        draining.set(false); queued.remove().run();
        verify(cold).observeWallet(walletId); verify(custodial).observeWallet(walletId);
        assertThat(queued).isEmpty(); verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void missingGuardCannotConsumePendingWork(boolean all) {
        service.setMaintenanceGuard(KfeMaintenanceGuard.unavailable()); enqueue(all);
        queued.remove().run(); noEffects(); verifyNoInteractions(store);
        assertThat(queued).hasSize(1);
        service.setMaintenanceGuard(guard); queued.remove().run();
        verify(cold).observeWallet(walletId); assertThat(queued).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void admissionStoreOutagePreservesPendingWorkAndCannotCallObservers(boolean all) {
        when(store.admit(anyString())).thenThrow(new IllegalStateException("synthetic store outage"));
        enqueue(all); queued.remove().run(); noEffects();
        assertThat(queued).hasSize(1); verify(store, never()).resolve(any(), anyBoolean());
    }

    @Test void admittedBatchRetainsOneRootThroughBothObserversWhenDrainBegins() {
        doAnswer(call -> { draining.set(true); verify(store, never()).resolve(any(), anyBoolean()); return null; })
                .when(cold).observeWallet(walletId);
        service.onWalletsTouched(Set.of(walletId)); queued.remove().run();
        verify(custodial).observeWallet(walletId); verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false); assertThat(queued).isEmpty();
    }

    @Test void signalCoalescingDoesNotDuplicateFinancialExecution() {
        service.onWalletsTouched(Set.of(walletId)); service.onWalletsTouched(Set.of(walletId)); service.onNewBlock();
        assertThat(queued).hasSize(1); queued.remove().run();
        verify(cold, times(1)).observeWallet(walletId); verify(custodial, times(1)).observeWallet(walletId);
        verify(store, times(1)).admit(anyString());
    }

    @Test void fallbackBalanceSyncIsStillWithinAdmittedUncertainBatch() {
        when(coldProvider.getIfAvailable()).thenReturn(null); when(custodialProvider.getIfAvailable()).thenReturn(null);
        service.onWalletsTouched(Set.of(walletId)); queued.remove().run();
        verify(sync).syncWallet(walletId); verify(store).resolve(admission.id(), false);
    }

    @Test void swallowedObserverFailureDoesNotProveRefreshCompletion() {
        doThrow(new IllegalStateException("synthetic observer failure")).when(cold).observeWallet(walletId);
        service.onWalletsTouched(Set.of(walletId)); queued.remove().run();
        verify(store).resolve(admission.id(), false); verifyNoInteractions(custodial);
        verify(store, never()).resolve(any(), eq(true));
    }

    @Test void inertInputDoesNotScheduleOrAdmitAndOwnedSchedulerIsClosed() {
        service.onWalletsTouched(null); service.onWalletsTouched(Set.of());
        assertThat(queued).isEmpty(); verifyNoInteractions(store, scheduler);
        noEffects(); service.shutdown(); verify(scheduler).shutdownNow();
    }

    private void enqueue(boolean all) { if (all) service.onNewBlock(); else service.onWalletsTouched(Set.of(walletId)); }
    private void noEffects() { verifyNoInteractions(index, coldProvider, custodialProvider, syncProvider, cold, custodial, sync); }
    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked") ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value); return provider;
    }
}
