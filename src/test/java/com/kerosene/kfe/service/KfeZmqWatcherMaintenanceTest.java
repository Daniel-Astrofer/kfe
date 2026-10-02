package com.kerosene.kfe.service;

import com.kerosene.kfe.maintenance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeZmqWatcherMaintenanceTest {
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final KfeMonitoredChainAddressIndex index = mock(KfeMonitoredChainAddressIndex.class);
    private final KfeColdWalletReactiveRefreshService refresh = mock(KfeColdWalletReactiveRefreshService.class);
    private final ObjectProvider<KfeColdWalletObservationService> cold = provider();
    private final ObjectProvider<KfeCustodialDepositObservationService> custodial = provider();
    private KfeBitcoinZmqWatcher watcher;

    @BeforeEach void setup() {
        watcher = new KfeBitcoinZmqWatcher(index, refresh, cold, custodial, "", "", true, "regtest");
        watcher.setMaintenanceGuard(guard); when(store.admit(anyString())).thenReturn(admission);
    }

    @ParameterizedTest @ValueSource(strings = {"hashblock", "rawblock", "rawtx"})
    void drainRejectsBeforeRefreshIngestOrSequenceChanges(String topic) {
        drain();
        assertThatThrownBy(() -> watcher.processMessage(topic, new byte[] {1}, 7))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects(); unchanged(); verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest @ValueSource(strings = {"hashblock", "rawblock", "rawtx"})
    void missingInjectionCannotStartCallbackOrAdvanceSequences(String topic) {
        watcher.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
        assertThatThrownBy(() -> watcher.processMessage(topic, new byte[] {1}, 7))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects(); unchanged(); verifyNoInteractions(store);
    }

    @ParameterizedTest @ValueSource(strings = {"hashblock", "rawblock", "rawtx"})
    void admissionStorageOutageCannotDispatchOrAdvanceSequences(String topic) {
        when(store.admit(anyString())).thenThrow(new IllegalStateException("synthetic storage outage"));
        assertThatThrownBy(() -> watcher.processMessage(topic, new byte[] {1}, 7))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects(); unchanged();
    }

    @Test void blockHandlerExecutesBeforeTelemetryAndRemainsUncertain() {
        doAnswer(call -> { unchanged(); verify(store, never()).resolve(any(), anyBoolean()); return null; })
                .when(refresh).onNewBlock();
        watcher.processMessage("hashblock", new byte[32], 7);
        assertThat(watcher.lastSequences()).containsEntry("hashblock", 7);
        verify(store).resolve(admission.id(), false);
    }

    @Test void propagatedCallbackFailureDoesNotAdvanceExistingTelemetry() {
        watcher.processMessage("hashblock", new byte[32], 7);
        doThrow(new IllegalStateException("synthetic refresh failure")).when(refresh).onNewBlock();
        assertThatThrownBy(() -> watcher.processMessage("hashblock", new byte[32], 8))
                .hasMessage("synthetic refresh failure");
        assertThat(watcher.lastSequences()).containsEntry("hashblock", 7);
        verify(store, never()).resolve(any(), eq(true));
    }

    @Test void rawGapRefreshRunsWithinAdmissionButIsNotDurableReplayProof() {
        watcher.processMessage("rawtx", null, 1);
        UUID wallet = UUID.randomUUID(); when(index.allColdWalletIds()).thenReturn(Set.of(wallet));
        watcher.processMessage("rawtx", null, 3);
        verify(refresh).onWalletsTouched(Set.of(wallet));
        assertThat(watcher.totalSequenceGaps()).isEqualTo(1);
        assertThat(watcher.lastSequences()).containsEntry("rawtx", 3);
        verify(store, times(2)).resolve(admission.id(), false);
    }

    @Test void rejectedCallbackKeepsPreviousSequenceAndAlreadyAdmittedParentCanFinish() {
        watcher.processMessage("hashblock", new byte[32], 7); drain();
        assertThatThrownBy(() -> watcher.processMessage("hashblock", new byte[32], 8))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        assertThat(watcher.lastSequences()).containsEntry("hashblock", 7);
        doReturn(admission).when(store).admit(anyString());
        guard.executeMutation("synthetic.parent", () -> { drain(); watcher.processMessage("hashblock", new byte[32], 8); return null; });
        assertThat(watcher.lastSequences()).containsEntry("hashblock", 8);
        verify(refresh, times(2)).onNewBlock();
    }

    @Test void observationalLifecycleWithNoConfiguredSocketDoesNotInventFinancialWork() {
        watcher.setMaintenanceGuard(KfeMaintenanceGuard.unavailable()); watcher.start();
        assertThat(watcher.isRunning()).isFalse(); watcher.stop(); watcher.destroy();
        assertThatThrownBy(() -> watcher.processMessage("unsupported", null, 1)).isInstanceOf(IllegalArgumentException.class);
        noEffects(); unchanged(); verifyNoInteractions(store);
    }

    private void drain() { when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain")); }
    private void unchanged() { assertThat(watcher.lastSequences()).isEmpty(); assertThat(watcher.totalSequenceGaps()).isZero(); }
    private void noEffects() { verifyNoInteractions(index, refresh, cold, custodial); }
    private static <T> ObjectProvider<T> provider() { @SuppressWarnings("unchecked") ObjectProvider<T> value = mock(ObjectProvider.class); return value; }
}
