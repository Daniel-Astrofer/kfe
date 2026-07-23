package com.kerosene.kfe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.application.channel.ChannelDecisionFlag;
import com.kerosene.kfe.application.channel.ChannelDecisionResult;
import com.kerosene.kfe.application.channel.ChannelFlagEvaluation;
import com.kerosene.kfe.application.channel.KfeChannelDecisionService;
import com.kerosene.kfe.dto.KfeChannelDecisionResponse;
import com.kerosene.kfe.dto.KfeOpenChannelRequest;
import com.kerosene.kfe.model.KfeChannelOperationDecisionEntity;
import com.kerosene.kfe.model.KfeChannelOperationType;
import com.kerosene.kfe.rail.ChannelsMeshInjectGateway;
import com.kerosene.kfe.rail.LightningChannelGateway;
import com.kerosene.kfe.repository.KfeChannelOperationDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeChannelLifecycleServiceMeshInjectTest {

    private final KfeChannelDecisionService decisionService = mock(KfeChannelDecisionService.class);
    private final LightningChannelGateway channelGateway = mock(LightningChannelGateway.class);
    private final ChannelsMeshInjectGateway channelsMeshInject = mock(ChannelsMeshInjectGateway.class);
    private final KfeChannelOperationDecisionRepository decisionRepository =
            mock(KfeChannelOperationDecisionRepository.class);
    private final KfeChannelRebalanceQueueService rebalanceQueueService =
            mock(KfeChannelRebalanceQueueService.class);
    private final KfeHashService hashService = mock(KfeHashService.class);
    private final KfeSystemWalletService systemWalletService = mock(KfeSystemWalletService.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);

    @BeforeEach
    void setUp() {
        when(hashService.sha256(anyString())).thenReturn("proposal-hash");
        when(systemWalletService.requireProfitWalletId()).thenReturn(UUID.randomUUID());
        when(channelGateway.isLive()).thenReturn(true);
        when(channelGateway.listChannels()).thenReturn(List.of());
        when(decisionService.evaluateOpen(anyString(), anyLong(), anyLong(), eq(true), anyString()))
                .thenReturn(
                        new ChannelDecisionResult(
                                KfeChannelOperationType.OPEN,
                                List.of(
                                        ChannelFlagEvaluation.pass(
                                                ChannelDecisionFlag.V_CAPITAL_MINIMO, "OK"),
                                        ChannelFlagEvaluation.pass(
                                                ChannelDecisionFlag.V_TAXA_ONCHAIN_BAIXA, "OK"),
                                        ChannelFlagEvaluation.pass(
                                                ChannelDecisionFlag.V_SAIDA_ANCORA, "OK"),
                                        ChannelFlagEvaluation.pass(
                                                ChannelDecisionFlag.V_AUTORIZACAO_MPC, "OK"),
                                        ChannelFlagEvaluation.pass(
                                                ChannelDecisionFlag.V_DENYLIST_PEER, "OK"),
                                        ChannelFlagEvaluation.pass(
                                                ChannelDecisionFlag.V_CHANNELS_MESH_INJECT,
                                                "CHANNELS_INJECT_READY"))));
        when(decisionRepository.save(any(KfeChannelOperationDecisionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(decisionRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.of(entityWithId(inv.getArgument(0))));
    }

    private static KfeChannelOperationDecisionEntity entityWithId(UUID id) {
        KfeChannelOperationDecisionEntity e = new KfeChannelOperationDecisionEntity();
        try {
            var field = KfeChannelOperationDecisionEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        e.setOperation(KfeChannelOperationType.OPEN);
        e.setPassed(true);
        e.setPeerPubkey("02abcd");
        e.setAmountSats(10_000_000L);
        e.setFlagsJson("{}");
        e.setDecisionReason("AND_PASS");
        e.setExecuted(false);
        return e;
    }

    private KfeChannelLifecycleService service(boolean requireInject) {
        return new KfeChannelLifecycleService(
                decisionService,
                channelGateway,
                channelsMeshInject,
                decisionRepository,
                rebalanceQueueService,
                hashService,
                systemWalletService,
                auditLogService,
                new ObjectMapper(),
                true,
                80,
                requireInject);
    }

    private static KfeOpenChannelRequest openRequest() {
        return new KfeOpenChannelRequest("02abcd", 10_000_000L, 10L, true, false, false);
    }

    @Test
    void openChannelReservesThenOpensThenCommits() {
        when(channelsMeshInject.reserveOpen(anyString(), eq(10_000_000L), eq("02abcd")))
                .thenAnswer(
                        inv ->
                                ChannelsMeshInjectGateway.DebitResult.ok(
                                        inv.getArgument(0), "CHANNELS_INJECT_RESERVED"));
        when(channelsMeshInject.commitOpen(anyString()))
                .thenReturn(ChannelsMeshInjectGateway.InjectResult.ok("CHANNELS_INJECT_COMMITTED"));
        when(channelGateway.openChannel(any()))
                .thenReturn(
                        new LightningChannelGateway.OpenChannelResult(
                                "txid", "0", "txid:0", "{}"));

        KfeChannelDecisionResponse response = service(true).openChannel(openRequest());

        assertThat(response.executed()).isTrue();
        assertThat(response.providerReference()).startsWith("MESH:channels-inject-open-");
        ArgumentCaptor<String> intentCaptor = ArgumentCaptor.forClass(String.class);
        verify(channelsMeshInject)
                .reserveOpen(intentCaptor.capture(), eq(10_000_000L), eq("02abcd"));
        verify(channelGateway).openChannel(any());
        verify(channelsMeshInject).commitOpen(intentCaptor.getValue());
        verify(channelsMeshInject, never()).releaseOpen(anyString(), anyLong(), anyString());
    }

    @Test
    void openChannelReleasesReservationWhenLndOpenFails() {
        when(channelsMeshInject.reserveOpen(anyString(), eq(10_000_000L), eq("02abcd")))
                .thenAnswer(
                        inv ->
                                ChannelsMeshInjectGateway.DebitResult.ok(
                                        inv.getArgument(0), "CHANNELS_INJECT_RESERVED"));
        when(channelsMeshInject.releaseOpen(anyString(), eq(10_000_000L), eq("02abcd")))
                .thenReturn(ChannelsMeshInjectGateway.InjectResult.ok("CHANNELS_INJECT_RELEASED"));
        when(channelGateway.openChannel(any()))
                .thenThrow(new IllegalStateException("lnd down"));

        KfeChannelDecisionResponse response = service(true).openChannel(openRequest());

        assertThat(response.executed()).isFalse();
        assertThat(response.decisionReason()).isEqualTo("OPEN_FAILED_AFTER_DEBIT:COMPENSATED");
        verify(channelsMeshInject).releaseOpen(anyString(), eq(10_000_000L), eq("02abcd"));
        verify(channelsMeshInject, never()).commitOpen(anyString());
    }

    @Test
    void openChannelRefusesDoubleOpenToSamePeer() {
        when(channelGateway.listChannels())
                .thenReturn(
                        List.of(
                                new LightningChannelGateway.ChannelSnapshot(
                                        "txid:0",
                                        "02ABCD",
                                        true,
                                        10_000_000L,
                                        5_000_000L,
                                        5_000_000L,
                                        0,
                                        true,
                                        100L)));

        KfeChannelDecisionResponse response = service(true).openChannel(openRequest());

        assertThat(response.executed()).isFalse();
        assertThat(response.decisionReason()).isEqualTo("CHANNEL_ALREADY_OPEN");
        verify(channelsMeshInject, never()).reserveOpen(anyString(), anyLong(), anyString());
        verify(channelGateway, never()).openChannel(any());
    }
}
