package com.kerosene.kfe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.kfe.model.KfeChannelRebalanceJobEntity;
import com.kerosene.kfe.model.KfeChannelRebalanceJobStatus;
import com.kerosene.kfe.rail.LightningChannelGateway;
import com.kerosene.kfe.rail.LightningLoopClient;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeChannelRebalanceWorkerTest {

    private final KfeChannelRebalanceQueueService queueService = mock(KfeChannelRebalanceQueueService.class);
    private final LightningChannelGateway channelGateway = mock(LightningChannelGateway.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<LightningLoopClient> loopProvider = mock(ObjectProvider.class);
    private final KfeSystemWalletService systemWalletService = mock(KfeSystemWalletService.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeLightningOpsMetrics> opsMetrics = mock(ObjectProvider.class);
    private final LightningLoopClient loopClient = mock(LightningLoopClient.class);
    private KfeChannelRebalanceWorker worker;

    @BeforeEach
    void setUp() {
        when(opsMetrics.getIfAvailable()).thenReturn(null);
        when(loopProvider.getIfAvailable()).thenReturn(null);
        when(channelGateway.isLive()).thenReturn(true);
        when(systemWalletService.requireProfitWalletId()).thenReturn(UUID.randomUUID());
        worker = new KfeChannelRebalanceWorker(
                queueService,
                channelGateway,
                loopProvider,
                systemWalletService,
                balanceService,
                auditLogService,
                opsMetrics,
                true,
                true,
                5,
                1_000L,
                50_000L);
    }

    @Test
    void successfulCircularCompletesJobAndSettlesFee() {
        UUID jobId = UUID.randomUUID();
        UUID profitId = UUID.randomUUID();
        when(systemWalletService.requireProfitWalletId()).thenReturn(profitId);

        KfeChannelRebalanceJobEntity job = pendingJob();
        when(queueService.findById(jobId)).thenReturn(Optional.of(job));
        when(channelGateway.attemptCircularRebalance(any()))
                .thenReturn(LightningChannelGateway.CircularRebalanceResult.ok("phash", 100L, "{}"));

        worker.executeJob(jobId);

        verify(queueService).markInProgress(jobId, "WORKER_START");
        verify(balanceService).reserve(eq(profitId), eq("BTC"), eq(500L));
        verify(balanceService).settleReservedDebit(eq(profitId), eq("BTC"), eq(100L));
        verify(balanceService).releaseReserved(eq(profitId), eq("BTC"), eq(400L));
        verify(queueService).complete(jobId, "phash");
        verify(loopClient, never()).loopIn(anyLong());
    }

    @Test
    void loopFallbackWhenCircularFails() {
        UUID jobId = UUID.randomUUID();
        UUID profitId = UUID.randomUUID();
        when(systemWalletService.requireProfitWalletId()).thenReturn(profitId);
        when(loopProvider.getIfAvailable()).thenReturn(loopClient);
        when(loopClient.isLive()).thenReturn(true);

        KfeChannelRebalanceJobEntity job = pendingJob();
        when(queueService.findById(jobId)).thenReturn(Optional.of(job));
        when(channelGateway.attemptCircularRebalance(any()))
                .thenReturn(LightningChannelGateway.CircularRebalanceResult.failed(
                        "NO_ROUTE", "{}", "no route"));
        when(channelGateway.listChannels()).thenReturn(java.util.List.of(
                new LightningChannelGateway.ChannelSnapshot(
                        "txid:0", "peer", true, 1_000_000L, 100_000L, 900_000L, 0, true, 0L, "99")));
        when(loopClient.loopIn(anyLong()))
                .thenReturn(LightningLoopClient.LoopResult.ok("LOOP_IN", "swap-1", "{}"));

        worker.executeJob(jobId);

        verify(loopClient).loopIn(10_000L);
        verify(queueService).complete(jobId, "swap-1");
        verify(balanceService).settleReservedDebit(eq(profitId), eq("BTC"), eq(500L));
    }

    @Test
    void failedCircularReleasesFeeReserveWhenNoLoop() {
        UUID jobId = UUID.randomUUID();
        UUID profitId = UUID.randomUUID();
        when(systemWalletService.requireProfitWalletId()).thenReturn(profitId);

        KfeChannelRebalanceJobEntity job = pendingJob();
        when(queueService.findById(jobId)).thenReturn(Optional.of(job));
        when(channelGateway.attemptCircularRebalance(any()))
                .thenReturn(LightningChannelGateway.CircularRebalanceResult.failed(
                        "FAILED", "{}", "no route"));

        worker.executeJob(jobId);

        verify(balanceService).releaseReserved(eq(profitId), eq("BTC"), eq(500L));
        verify(queueService).fail(eq(jobId), anyString());
        verify(queueService, never()).complete(any(), anyString());
    }

    private static KfeChannelRebalanceJobEntity pendingJob() {
        KfeChannelRebalanceJobEntity job = new KfeChannelRebalanceJobEntity();
        job.setChannelPoint("txid:0");
        job.setStatus(KfeChannelRebalanceJobStatus.PENDING);
        job.setEstimatedCostSats(500L);
        job.setExpectedGainSats(10_000L);
        return job;
    }
}
