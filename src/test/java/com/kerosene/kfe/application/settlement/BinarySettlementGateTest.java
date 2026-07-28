package com.kerosene.kfe.application.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import com.kerosene.kfe.model.KfeBalanceEntity;
import com.kerosene.kfe.model.KfeBalanceId;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.repository.KfeBalanceRepository;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.service.KfeBalanceService;
import com.kerosene.kfe.service.KfeLightningJammingGuard;
import com.kerosene.kfe.service.KfeLightningLiquidityService;
import com.kerosene.kfe.service.KfeCapacitySignalStore;
import com.kerosene.kfe.service.KfeLightningOpsMetrics;
import com.kerosene.kfe.service.KfeProofOfReservesService;
import com.kerosene.kfe.service.KfeQuorumGateway;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BinarySettlementGateTest {

    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeBalanceRepository balanceRepository = mock(KfeBalanceRepository.class);
    private final KfeProofOfReservesService porService = mock(KfeProofOfReservesService.class);
    private final KfeQuorumGateway quorumGateway = mock(KfeQuorumGateway.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    private final KfeLightningLiquidityService liquidityService = mock(KfeLightningLiquidityService.class);
    private final KfeLightningJammingGuard jammingGuard = mock(KfeLightningJammingGuard.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeLightningOpsMetrics> opsMetrics = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeCapacitySignalStore> capacitySignals = mock(ObjectProvider.class);
    private final Environment environment = mock(Environment.class);

    private BinarySettlementGate gate;

    @BeforeEach
    void setUp() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"test"});
        when(liquidityService.isLive()).thenReturn(false);
        when(liquidityService.outboundCapacitySats()).thenReturn(-1L);
        when(liquidityService.freeOutboundCapacitySats()).thenReturn(-1L);
        when(liquidityService.canCoverOutbound(org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);
        when(liquidityService.circuitBreakerOpen()).thenReturn(false);
        when(jammingGuard.evaluate())
                .thenReturn(KfeLightningJammingGuard.JammingCheck.softPass("BETA_LIMITED:TEST"));
        when(opsMetrics.getIfAvailable()).thenReturn(null);
        when(capacitySignals.getIfAvailable()).thenReturn(null);
        when(porService.isEnabled()).thenReturn(false);
        when(balanceRepository.findAll()).thenReturn(java.util.List.of());
        gate = new BinarySettlementGate(
                balanceService,
                balanceRepository,
                porService,
                quorumGateway,
                auditLogService,
                liquidityService,
                jammingGuard,
                opsMetrics,
                capacitySignals,
                environment,
                "beta-pass",
                false,
                false,
                3,
                2);
    }

    @Test
    void allFlagsPassForInternalTransferWithoutReserve() {
        UUID txId = UUID.randomUUID();
        when(quorumGateway.requireHealthyUnanimousConsensus("proposal"))
                .thenReturn(new KfeQuorumGateway.Result(3, 3));

        SettlementGateResult result = gate.evaluate(command(
                txId,
                null,
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                10_000L,
                0L,
                10_000L,
                false,
                "proposal"));

        assertThat(result.passed()).isTrue();
        assertThat(result.evaluations()).hasSize(SettlementFlag.values().length);
        assertThat(result.byFlag().get(SettlementFlag.V_LIQUIDEZ).reason()).isEqualTo("NOT_APPLICABLE");
        assertThat(result.byFlag().get(SettlementFlag.V_ASSINATURA_MPC).pass()).isTrue();
        assertThat(result.byFlag().get(SettlementFlag.V_ASSINATURA_MPC).reason())
                .startsWith("QUORUM_THRESHOLD_MET:");
        assertThat(result.quorumAckCount()).isEqualTo(3);
        verify(balanceService, never()).requireForUpdate(any(), anyString());
    }

    @Test
    void insufficientAvailableFailsSaldoFlagOnly() {
        UUID walletId = UUID.randomUUID();
        KfeBalanceEntity balance = balance(walletId, 500L);
        when(balanceService.requireForUpdate(walletId, "BTC")).thenReturn(balance);
        when(quorumGateway.requireHealthyUnanimousConsensus("proposal"))
                .thenReturn(new KfeQuorumGateway.Result(3, 3));

        SettlementGateResult result = gate.evaluate(command(
                UUID.randomUUID(),
                walletId,
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                1_000L,
                100L,
                1_100L,
                true,
                "proposal"));

        assertThat(result.passed()).isFalse();
        assertThat(result.failedFlags()).containsExactly(SettlementFlag.V_SALDO_DISP);
        assertThat(result.byFlag().get(SettlementFlag.V_LOCK_BANDO).pass()).isTrue();
        assertThat(result.byFlag().get(SettlementFlag.V_SALDO_DISP).reason())
                .isEqualTo("INSUFFICIENT_AVAILABLE");
    }

    @Test
    void enforceModeFailsWhenLightningCapacityUnavailable() {
        gate = new BinarySettlementGate(
                balanceService,
                balanceRepository,
                porService,
                quorumGateway,
                auditLogService,
                liquidityService,
                jammingGuard,
                opsMetrics,
                capacitySignals,
                environment,
                "enforce",
                false,
                false,
                3,
                2);
        UUID walletId = UUID.randomUUID();
        when(balanceService.requireForUpdate(walletId, "BTC")).thenReturn(balance(walletId, 1_000_000L));
        when(quorumGateway.requireHealthyUnanimousConsensus("proposal"))
                .thenReturn(new KfeQuorumGateway.Result(3, 3));
        when(liquidityService.isLive()).thenReturn(false);

        SettlementGateResult result = gate.evaluate(command(
                UUID.randomUUID(),
                walletId,
                KfeRail.LIGHTNING,
                KfeDirection.OUTBOUND,
                1_000L,
                10L,
                1_010L,
                true,
                "proposal"));

        assertThat(result.passed()).isFalse();
        assertThat(result.failedFlags()).contains(SettlementFlag.V_LIQUIDEZ);
    }

    @Test
    void jammingHardBlockFailsNoJammingFlag() {
        UUID walletId = UUID.randomUUID();
        when(balanceService.requireForUpdate(walletId, "BTC")).thenReturn(balance(walletId, 1_000_000L));
        when(quorumGateway.requireHealthyUnanimousConsensus("proposal"))
                .thenReturn(new KfeQuorumGateway.Result(3, 3));
        when(liquidityService.isLive()).thenReturn(true);
        when(liquidityService.freeOutboundCapacitySats()).thenReturn(1_000_000L);
        when(liquidityService.canCoverOutbound(1_010L)).thenReturn(true);
        when(jammingGuard.evaluate())
                .thenReturn(KfeLightningJammingGuard.JammingCheck.blocked("PENDING_HTLC_LIMIT:50>=48"));

        SettlementGateResult result = gate.evaluate(command(
                UUID.randomUUID(),
                walletId,
                KfeRail.LIGHTNING,
                KfeDirection.OUTBOUND,
                1_000L,
                10L,
                1_010L,
                true,
                "proposal"));

        assertThat(result.passed()).isFalse();
        assertThat(result.failedFlags()).containsExactly(SettlementFlag.V_NO_JAMMING);
    }

    @Test
    void liveCapacityAllowsLightningLiquidityFlag() {
        UUID walletId = UUID.randomUUID();
        when(balanceService.requireForUpdate(walletId, "BTC")).thenReturn(balance(walletId, 1_000_000L));
        when(quorumGateway.requireHealthyUnanimousConsensus("proposal"))
                .thenReturn(new KfeQuorumGateway.Result(3, 3));
        when(liquidityService.isLive()).thenReturn(true);
        when(liquidityService.outboundCapacitySats()).thenReturn(5_000_000L);
        when(liquidityService.freeOutboundCapacitySats()).thenReturn(5_000_000L);
        when(liquidityService.canCoverOutbound(1_010L)).thenReturn(true);
        when(liquidityService.circuitBreakerOpen()).thenReturn(false);

        SettlementGateResult result = gate.evaluate(command(
                UUID.randomUUID(),
                walletId,
                KfeRail.LIGHTNING,
                KfeDirection.OUTBOUND,
                1_000L,
                10L,
                1_010L,
                true,
                "proposal"));

        assertThat(result.passed()).isTrue();
        assertThat(result.byFlag().get(SettlementFlag.V_LIQUIDEZ).reason())
                .startsWith("FREE_OUTBOUND_CAPACITY_OK:");
    }

    @Test
    void betaPassAllowsLightningWhenCapacityUnavailable() {
        UUID walletId = UUID.randomUUID();
        when(balanceService.requireForUpdate(walletId, "BTC")).thenReturn(balance(walletId, 1_000_000L));
        when(quorumGateway.requireHealthyUnanimousConsensus("proposal"))
                .thenReturn(new KfeQuorumGateway.Result(3, 3));

        SettlementGateResult result = gate.evaluate(command(
                UUID.randomUUID(),
                walletId,
                KfeRail.LIGHTNING,
                KfeDirection.OUTBOUND,
                1_000L,
                10L,
                1_010L,
                true,
                "proposal"));

        assertThat(result.passed()).isTrue();
        assertThat(result.byFlag().get(SettlementFlag.V_LIQUIDEZ).reason())
                .startsWith("BETA_LIMITED:");
    }

    @Test
    void evaluateAndRequirePassAuditsAndThrowsWhenRejected() {
        UUID txId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        when(balanceService.requireForUpdate(walletId, "BTC")).thenReturn(balance(walletId, 1L));
        when(quorumGateway.requireHealthyUnanimousConsensus("proposal"))
                .thenReturn(new KfeQuorumGateway.Result(3, 3));

        SettlementGateCommand command = command(
                txId,
                walletId,
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                100L,
                0L,
                100L,
                true,
                "proposal");

        assertThatThrownBy(() -> gate.evaluateAndRequirePass(command))
                .isInstanceOf(SettlementGateRejectedException.class)
                .hasMessageContaining("V_SALDO_DISP");

        verify(auditLogService).record(
                eq("KFE_SETTLEMENT_GATE"),
                eq(txId),
                eq(walletId),
                any(),
                any(),
                anyMap());
    }

    @Test
    void atomicidadeRejectsNonPositiveAmount() {
        when(quorumGateway.requireHealthyUnanimousConsensus("proposal"))
                .thenReturn(new KfeQuorumGateway.Result(3, 3));

        SettlementGateResult result = gate.evaluate(command(
                UUID.randomUUID(),
                null,
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                0L,
                0L,
                0L,
                false,
                "proposal"));

        assertThat(result.passed()).isFalse();
        assertThat(result.failedFlags()).contains(SettlementFlag.V_ATOMICIDADE);
    }

    @Test
    void mpcFailureIsFlagZero() {
        when(quorumGateway.requireHealthyUnanimousConsensus("proposal"))
                .thenThrow(new IllegalStateException("quorum down"));

        SettlementGateResult result = gate.evaluate(command(
                UUID.randomUUID(),
                null,
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                10L,
                0L,
                10L,
                false,
                "proposal"));

        assertThat(result.passed()).isFalse();
        assertThat(result.byFlag().get(SettlementFlag.V_ASSINATURA_MPC).pass()).isFalse();
        assertThat(result.byFlag().get(SettlementFlag.V_ASSINATURA_MPC).reason())
                .startsWith("QUORUM_REJECTED:");
    }

    @Test
    void mpcFailsWhenAcceptedBelowConstitutionThreshold() {
        when(quorumGateway.requireHealthyUnanimousConsensus("proposal"))
                .thenReturn(new KfeQuorumGateway.Result(1, 3));

        SettlementGateResult result = gate.evaluate(command(
                UUID.randomUUID(),
                null,
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                10L,
                0L,
                10L,
                false,
                "proposal"));

        assertThat(result.passed()).isFalse();
        assertThat(result.byFlag().get(SettlementFlag.V_ASSINATURA_MPC).pass()).isFalse();
        assertThat(result.byFlag().get(SettlementFlag.V_ASSINATURA_MPC).reason())
                .startsWith("QUORUM_THRESHOLD_NOT_MET:");
    }

    @Test
    void mpcPassesWithExactlyThresholdAcceptances() {
        when(quorumGateway.requireHealthyUnanimousConsensus("proposal"))
                .thenReturn(new KfeQuorumGateway.Result(2, 3));

        SettlementGateResult result = gate.evaluate(command(
                UUID.randomUUID(),
                null,
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                10L,
                0L,
                10L,
                false,
                "proposal"));

        assertThat(result.passed()).isTrue();
        assertThat(result.byFlag().get(SettlementFlag.V_ASSINATURA_MPC).pass()).isTrue();
        assertThat(result.byFlag().get(SettlementFlag.V_ASSINATURA_MPC).reason())
                .startsWith("QUORUM_THRESHOLD_MET:");
    }

    private static SettlementGateCommand command(
            UUID txId,
            UUID sourceWalletId,
            KfeRail rail,
            KfeDirection direction,
            long amount,
            long fee,
            long totalDebit,
            boolean requiresReserve,
            String proposalHash) {
        return new SettlementGateCommand(
                1L,
                txId,
                sourceWalletId,
                "idemp-key",
                true,
                rail,
                direction,
                amount,
                fee,
                totalDebit,
                requiresReserve,
                proposalHash);
    }

    private static KfeBalanceEntity balance(UUID walletId, long available) {
        KfeBalanceEntity entity = new KfeBalanceEntity();
        entity.setId(new KfeBalanceId(walletId, "BTC"));
        entity.setAvailableSats(available);
        entity.setLockedSats(0L);
        entity.setPendingSats(0L);
        entity.setAutoHoldSats(0L);
        entity.setObservedSats(0L);
        entity.setNonce(0L);
        entity.setLastHash("hash");
        entity.setBalanceSignature("hash");
        return entity;
    }
}
