package com.kerosene.kfe.application.settlement;

import com.kerosene.common.financial.FinancialQuorumPort;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;
import com.kerosene.kfe.maintenance.KfeMaintenanceService;
import com.kerosene.kfe.maintenance.KfeMaintenanceStore;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.repository.KfeBalanceRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeSettlementMaintenanceTest {
    enum Root { EVALUATE, REQUIRE_PASS, AUDIT, LEGACY_AUDIT, QUORUM }
    enum Rejection { DRAINING, UNAVAILABLE, STORE_FAILURE }
    enum Completion { DIRECT, COMMIT, ROLLBACK, UNKNOWN }
    final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    final KfeMaintenanceStore.Admission admission = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    final FinancialQuorumPort port = mock(FinancialQuorumPort.class);
    final KfeQuorumGateway quorum = new KfeQuorumGateway(port);
    final KfeBalanceService balances = mock(KfeBalanceService.class);
    final KfeBalanceRepository balanceRepository = mock(KfeBalanceRepository.class);
    final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    final KfeProofOfReservesService por = mock(KfeProofOfReservesService.class);
    final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    final KfeLightningLiquidityService liquidity = mock(KfeLightningLiquidityService.class);
    final KfeLightningJammingGuard jamming = mock(KfeLightningJammingGuard.class);
    @SuppressWarnings("unchecked")
    final ObjectProvider<KfeLightningOpsMetrics> metrics = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    final ObjectProvider<KfeCapacitySignalStore> signals = mock(ObjectProvider.class);
    final Environment environment = mock(Environment.class);
    BinarySettlementGate gate;
    final SettlementGateCommand command = new SettlementGateCommand(1L, UUID.randomUUID(), null,
            "key", true, KfeRail.INTERNAL, KfeDirection.INTERNAL, 100L, 0L, 100L, false, "hash");
    final SettlementGateResult auditResult = new SettlementGateResult(java.util.List.of(), 2, 3);

    @BeforeEach
    void setup() {
        lenient().when(store.admit(anyString())).thenReturn(admission);
        lenient().when(port.requireHealthyUnanimousConsensus("hash"))
                .thenReturn(new FinancialQuorumPort.Result(2, 3));
        lenient().when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        gate = new BinarySettlementGate(balances, balanceRepository, wallets, por, quorum, audit,
                liquidity, jamming, metrics, signals, environment, "enforce", false, false, 3, 2);
        gate.setMaintenanceGuard(guard);
        quorum.setMaintenanceGuard(guard);
    }

    @AfterEach
    void cleanup() { TransactionSynchronizationManager.clear(); }

    static Stream<Arguments> rejections() {
        return Arrays.stream(Root.values()).flatMap(root -> Arrays.stream(Rejection.values())
                .map(rejection -> Arguments.of(root, rejection)));
    }

    static Stream<Arguments> completions() {
        return Arrays.stream(Root.values()).flatMap(root -> Arrays.stream(Completion.values())
                .map(completion -> Arguments.of(root, completion)));
    }

    @ParameterizedTest
    @MethodSource("rejections")
    void rejectsBeforeAnyEvaluationOrProviderEffect(Root root, Rejection rejection) {
        switch (rejection) {
            case DRAINING -> doThrow(new KfeMaintenanceGuard.MaintenanceException(503, "drain"))
                    .when(store).admit(anyString());
            case STORE_FAILURE -> doThrow(new IllegalStateException("storage outage"))
                    .when(store).admit(anyString());
            case UNAVAILABLE -> {
                gate.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
                quorum.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
            }
        }
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(port, balances, balanceRepository, wallets, por, audit,
                liquidity, jamming, metrics, signals, environment);
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest
    @MethodSource("completions")
    void successAndFinancialCommitNeverProveRemoteCompletion(Root root, Completion completion) {
        if (completion != Completion.DIRECT) {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
        }
        Object result = invoke(root);
        if (root == Root.EVALUATE || root == Root.REQUIRE_PASS) {
            assertThat(((SettlementGateResult) result).passed()).isTrue();
            assertThat(((SettlementGateResult) result).quorumAckCount()).isEqualTo(2);
        }
        verify(store, times(1)).admit(anyString());
        if (completion != Completion.DIRECT) {
            verify(store, never()).resolve(any(), anyBoolean());
            int status = switch (completion) {
                case COMMIT -> TransactionSynchronization.STATUS_COMMITTED;
                case ROLLBACK -> TransactionSynchronization.STATUS_ROLLED_BACK;
                default -> TransactionSynchronization.STATUS_UNKNOWN;
            };
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));
        }
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest
    @EnumSource(value = Root.class, names = {"EVALUATE", "REQUIRE_PASS", "QUORUM"})
    void transportRejectionCannotBecomeCompletion(Root root) {
        doThrow(new IllegalStateException("provider unavailable")).when(port)
                .requireHealthyUnanimousConsensus("hash");
        if (root == Root.EVALUATE) {
            SettlementGateResult result = (SettlementGateResult) invoke(root);
            assertThat(result.passed()).isFalse();
            assertThat(result.byFlag().get(SettlementFlag.V_ASSINATURA_MPC).reason())
                    .startsWith("QUORUM_REJECTED:");
        } else {
            assertThatThrownBy(() -> invoke(root)).isInstanceOf(RuntimeException.class);
        }
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void actualTransactionWithoutCompletionObservationRefusesEffects(Root root) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(port, balances, audit, metrics, signals, environment);
    }

    @Test
    void admittedWorkflowCanFinishAfterDrainWithoutNewAdmission() {
        guard.executeMutation("outer", () -> {
            doThrow(new KfeMaintenanceGuard.MaintenanceException(503, "drain"))
                    .when(store).admit(anyString());
            assertThat(gate.evaluateAndRequirePass(command).passed()).isTrue();
            return null;
        }, ignored -> true);
        verify(store, times(1)).admit("outer");
        verify(port).requireHealthyUnanimousConsensus("hash");
        verify(store).resolve(admission.id(), false);
        assertThatThrownBy(() -> gate.evaluate(command)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
    }

    @Test
    void setterRefusesNull() {
        assertThatNullPointerException().isThrownBy(() -> gate.setMaintenanceGuard(null));
        assertThatNullPointerException().isThrownBy(() -> quorum.setMaintenanceGuard(null));
    }

    @Test
    void defaultConstructionRefusesWithoutInjection() {
        BinarySettlementGate unconfigured = new BinarySettlementGate(balances, balanceRepository,
                wallets, por, quorum, audit, liquidity, jamming, metrics, signals,
                environment, "enforce", false, false, 3, 2);
        KfeQuorumGateway unconfiguredQuorum = new KfeQuorumGateway(port);
        assertThatThrownBy(() -> unconfigured.evaluate(command))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        assertThatThrownBy(() -> unconfiguredQuorum.requireHealthyUnanimousConsensus("hash"))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(port, balances, audit, environment);
    }

    @Test
    void liquidityFailureSignalsStayInsideAdmissionAndRemainUncertain() {
        KfeLightningOpsMetrics metric = mock(KfeLightningOpsMetrics.class);
        KfeCapacitySignalStore signal = mock(KfeCapacitySignalStore.class);
        when(metrics.getIfAvailable()).thenReturn(metric);
        when(signals.getIfAvailable()).thenReturn(signal);
        when(jamming.evaluate()).thenReturn(KfeLightningJammingGuard.JammingCheck.allowed("ok"));
        SettlementGateCommand lightning = new SettlementGateCommand(1L, command.transactionId(),
                null, "key", true, KfeRail.LIGHTNING, KfeDirection.OUTBOUND,
                100L, 0L, 100L, false, "hash");
        doAnswer(call -> {
            verify(store).admit("settlement.require-pass");
            return null;
        }).when(signal).recordLiquidityReject();
        assertThatThrownBy(() -> gate.evaluateAndRequirePass(lightning))
                .isInstanceOf(SettlementGateRejectedException.class);
        verify(signal).recordLiquidityReject();
        verify(metric).recordSettlementGate("fail");
        verify(store).resolve(admission.id(), false);
    }

    @SuppressWarnings("deprecation")
    Object invoke(Root root) {
        return switch (root) {
            case EVALUATE -> gate.evaluate(command);
            case REQUIRE_PASS -> gate.evaluateAndRequirePass(command);
            case QUORUM -> quorum.requireHealthyUnanimousConsensus("hash");
            case AUDIT -> {
                gate.persistGateAuditInCallerTransaction(command.transactionId(), null, auditResult);
                yield null;
            }
            case LEGACY_AUDIT -> {
                gate.persistGateAudit(command.transactionId(), null, auditResult);
                yield null;
            }
        };
    }
}
