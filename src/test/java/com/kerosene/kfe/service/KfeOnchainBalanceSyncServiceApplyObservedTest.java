package com.kerosene.kfe.service;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;
import com.kerosene.kfe.maintenance.KfeMaintenanceService;
import com.kerosene.kfe.maintenance.KfeMaintenanceStore;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.rail.BlockchainClient;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KfeOnchainBalanceSyncServiceApplyObservedTest {

    @Test
    void liveMempoolAwareAlwaysWritesForCold() {
        var probe = ChainProbeResult.liveMempoolAware(631_477L, 4, "cold-collect");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.WATCH_ONLY, 791_177L, probe);
        assertThat(decision.write()).isTrue();
        assertThat(decision.reason()).isEqualTo("live-mempool-aware");
    }

    @Test
    void confirmedUtxoSetDoesNotClobberNonZeroCold() {
        var probe = ChainProbeResult.confirmedUtxoSet(791_177L, 5, "syncWallet-descriptor");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.WATCH_ONLY, 631_477L, probe);
        assertThat(decision.write()).isFalse();
        assertThat(decision.reason()).contains("will-not-clobber");
    }

    @Test
    void confirmedUtxoSetSeedsEmptyCold() {
        var probe = ChainProbeResult.confirmedUtxoSet(100_000L, 1, "import");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.WATCH_ONLY, 0L, probe);
        assertThat(decision.write()).isTrue();
    }

    @Test
    void optimisticZeroRefusedWhenPreviousPositive() {
        var probe = ChainProbeResult.optimisticDelta(0L, "zmq-optimistic");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.WATCH_ONLY, 791_177L, probe);
        assertThat(decision.write()).isFalse();
        assertThat(decision.reason()).isEqualTo("optimistic-zero-refused");
    }

    @Test
    void optimisticDeltaAllowsPartialDrop() {
        var probe = ChainProbeResult.optimisticDelta(631_477L, "zmq-optimistic");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.WATCH_ONLY, 791_177L, probe);
        assertThat(decision.write()).isTrue();
    }

    @Test
    void optimisticDeltaRefusesImplausibleWipe() {
        // e.g. multi-output funding debited as whole inbound → next << previous
        var probe = ChainProbeResult.optimisticDelta(5_000L, "zmq-optimistic");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.WATCH_ONLY, 791_177L, probe);
        assertThat(decision.write()).isFalse();
        assertThat(decision.reason()).isEqualTo("optimistic-drop-too-large");
    }

    @Test
    void optimisticDoesNotClobberFreshLive() {
        var probe = ChainProbeResult.optimisticDelta(600_000L, "zmq-optimistic");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.WATCH_ONLY,
                631_477L,
                ProbeQuality.LIVE_MEMPOOL_AWARE,
                LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(10),
                probe,
                120L);
        assertThat(decision.write()).isFalse();
        assertThat(decision.reason()).isEqualTo("optimistic-will-not-clobber-fresh-live");
    }

    @Test
    void optimisticAllowedWhenLiveIsStale() {
        var probe = ChainProbeResult.optimisticDelta(600_000L, "zmq-optimistic");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.WATCH_ONLY,
                631_477L,
                ProbeQuality.LIVE_MEMPOOL_AWARE,
                LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(300),
                probe,
                120L);
        assertThat(decision.write()).isTrue();
        assertThat(decision.reason()).isEqualTo("optimistic-delta");
    }

    @Test
    void liveOverwritesOptimistic() {
        var probe = ChainProbeResult.liveMempoolAware(500_000L, 2, "cold-collect");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.WATCH_ONLY,
                600_000L,
                ProbeQuality.OPTIMISTIC_DELTA,
                LocalDateTime.now(),
                probe,
                120L);
        assertThat(decision.write()).isTrue();
        assertThat(decision.reason()).isEqualTo("live-mempool-aware");
    }

    @Test
    void unknownNeverWrites() {
        var probe = ChainProbeResult.unknown("scan-failed");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.WATCH_ONLY, 100L, probe);
        assertThat(decision.write()).isFalse();
    }

    @Test
    void custodialAlwaysAcceptsAbsoluteProbe() {
        var probe = ChainProbeResult.confirmedUtxoSet(50_000L, 2, "sync");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.CUSTODIAL_ONCHAIN, 40_000L, probe);
        assertThat(decision.write()).isTrue();
    }

    @Test
    void liveFullySpentZeroIsAuthoritative() {
        var probe = ChainProbeResult.liveMempoolAware(0L, 0, "cold-collect");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.WATCH_ONLY, 100_000L, probe);
        assertThat(decision.write()).isTrue();
    }

    @Test
    void nonAuthoritativeLiveProbeStillCannotReplaceColdBalance() {
        var probe = new ChainProbeResult(50_000L, ProbeQuality.LIVE_MEMPOOL_AWARE, false, 2, "partial");
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.WATCH_ONLY, 100_000L, probe);
        assertThat(decision.write()).isFalse();
        assertThat(decision.reason()).isEqualTo("live-not-authoritative");
    }

    @Test
    void custodialAuthoritativeZeroStillReplacesPreviousAbsoluteMirror() {
        var decision = KfeOnchainBalanceSyncService.decideWrite(
                KfeWalletKind.CUSTODIAL_ONCHAIN, 100_000L,
                ChainProbeResult.liveMempoolAware(0L, 0, "fully-spent"));
        assertThat(decision.write()).isTrue();
        assertThat(decision.reason()).isEqualTo("custodial-absolute");
    }

    @Test
    void directPackageProbeCannotBypassDrainingAdmission() {
        KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
        when(store.admit("balance-observation.probe-chain"))
                .thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "KFE is draining."));
        BlockchainClient client = mock(BlockchainClient.class);
        // Null collaborators make any lookup/resolution before admission fail this test.
        var service = new KfeOnchainBalanceSyncService(
                null, null, null, null, null, null, null, null, 2, 1000, 120);
        service.setMaintenanceGuard(new KfeMaintenanceService(store));
        assertThatThrownBy(() -> service.probeChainBalanceSats(client, null))
                .isInstanceOfSatisfying(KfeMaintenanceGuard.MaintenanceException.class,
                        failure -> assertThat(failure.httpStatus()).isEqualTo(503));
        verify(store).admit("balance-observation.probe-chain");
        verifyNoMoreInteractions(store);
        verifyNoInteractions(client);
    }
}
