package com.kerosene.kfe.maintenance;

import com.kerosene.kfe.application.transaction.KfeBalanceMovementRecorder;
import com.kerosene.kfe.application.transaction.KfeLedgerMovementTypes;
import com.kerosene.kfe.model.KfeBalanceMovementEntity;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.repository.KfeBalanceMovementRepository;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.service.KfeBalanceMetrics;
import com.kerosene.kfe.service.KfeBalanceService;
import com.kerosene.kfe.service.KfeFeeSettlementService;
import com.kerosene.kfe.service.KfeSystemWalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeFeeMovementMaintenanceTest {
    private enum Root {
        CREDIT("fee.credit", KfeLedgerMovementTypes.CREDIT_KEROSENE_FEE, null, "AVAILABLE"),
        REVERSE("fee.reverse-reorg", KfeLedgerMovementTypes.REVERSAL_KEROSENE_FEE,
                "AVAILABLE_OR_DEBT", "CHAIN_REORG"),
        RESTORE("fee.restore-reorg", KfeLedgerMovementTypes.RESTORE_KEROSENE_FEE,
                "CHAIN_REORG", "AVAILABLE_OR_DEBT"),
        MOVEMENT("balance-movement.record", KfeLedgerMovementTypes.CREDIT_INBOUND, null, "AVAILABLE");

        final String operation;
        final String movementType;
        final String fromBucket;
        final String toBucket;

        Root(String operation, String movementType, String fromBucket, String toBucket) {
            this.operation = operation;
            this.movementType = movementType;
            this.fromBucket = fromBucket;
            this.toBucket = toBucket;
        }
    }

    private enum Rejection { DRAINING, MISSING_INJECTION, STORE_OUTAGE }
    private enum Completion { DIRECT, COMMIT, ROLLBACK, UNKNOWN }

    private final KfeMaintenanceStore mockStore = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(mockStore);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private KfeMaintenanceStore.Control control =
            new KfeMaintenanceStore.Control(KfeMaintenanceGuard.Mode.ACTIVE, null, 0);
    private final KfeBalanceMovementRepository movements = mock(KfeBalanceMovementRepository.class);
    private final KfeSystemWalletService wallets = mock(KfeSystemWalletService.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeBalanceMetrics> metricsProvider = mock(ObjectProvider.class);
    private final KfeBalanceMetrics metrics = mock(KfeBalanceMetrics.class);
    private KfeBalanceMovementRecorder recorder = new KfeBalanceMovementRecorder(movements);
    private KfeFeeSettlementService fees;
    private final UUID profitWalletId = UUID.randomUUID();
    private final KfeTransactionEntity tx = new KfeTransactionEntity();

    @BeforeEach
    void setup() {
        tx.setKeroseneFeeSats(900L);
        tx.setStatus(KfeTransactionStatus.SETTLED);
        recorder.setMaintenanceGuard(guard);
        fees = feeService();
        fees.setMaintenanceGuard(guard);
        lenient().when(mockStore.admit(anyString())).thenAnswer(call -> {
            if (control.mode() == KfeMaintenanceGuard.Mode.DRAINING) {
                throw new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain");
            }
            return admission;
        });
        lenient().when(mockStore.transition(any(), any(), anyLong())).thenAnswer(call -> {
            KfeMaintenanceGuard.Command command = call.getArgument(1);
            control = new KfeMaintenanceStore.Control(
                    KfeMaintenanceGuard.Mode.DRAINING, command.changeId(), control.revision() + 1);
            return control;
        });
        lenient().when(mockStore.observe()).thenAnswer(call ->
                new KfeMaintenanceStore.Observation(control, Instant.now(), Map.of()));
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clear();
    }

    static Stream<Arguments> rejections() {
        return Arrays.stream(Root.values()).flatMap(root ->
                Arrays.stream(Rejection.values()).map(rejection -> Arguments.of(root, rejection)));
    }

    @ParameterizedTest
    @MethodSource("rejections")
    void rejectionPrecedesAllRepositoryAndFinancialEffects(Root root, Rejection rejection) {
        switch (rejection) {
            case DRAINING -> drain();
            case MISSING_INJECTION -> {
                recorder = new KfeBalanceMovementRecorder(movements);
                fees = feeService();
            }
            case STORE_OUTAGE -> doThrow(new IllegalStateException("synthetic store outage"))
                    .when(mockStore).admit(anyString());
        }

        assertThatThrownBy(() -> invoke(root))
                .isInstanceOfSatisfying(KfeMaintenanceGuard.MaintenanceException.class,
                        exception -> assertThat(exception.httpStatus()).isEqualTo(503));

        verifyNoInteractions(movements, wallets, balances, audit, metricsProvider, metrics);
        assertThat(tx.getKeroseneFeeSats()).isEqualTo(900L);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        verify(mockStore, never()).resolve(any(), anyBoolean());
        if (rejection == Rejection.MISSING_INJECTION) {
            verifyNoInteractions(mockStore);
        } else {
            verify(mockStore).admit(root.operation);
        }
    }

    @Test
    void pureFeeNoopsAndConfigurationRemainAccessibleWithoutInjection() {
        fees = feeService();
        KfeTransactionEntity zero = new KfeTransactionEntity();
        zero.setKeroseneFeeSats(0L);
        KfeTransactionEntity negative = new KfeTransactionEntity();
        negative.setKeroseneFeeSats(-1L);
        KfeTransactionEntity missingId = mock(KfeTransactionEntity.class);
        when(missingId.getId()).thenReturn(null);
        for (KfeTransactionEntity input : new KfeTransactionEntity[]{null, zero, negative, missingId}) {
            fees.creditKeroseneFee(input);
            fees.reverseKeroseneFeeForReorg(input);
            fees.restoreKeroseneFeeAfterReorg(input);
        }
        assertThat(fees.profitSegregationMode()).isEqualTo("SUBLEDGER");
        assertThat(fees.profitReconcileWithVault()).isTrue();
        drain();
        fees.setMaintenanceGuard(guard);
        fees.creditKeroseneFee(zero);
        fees.reverseKeroseneFeeForReorg(zero);
        fees.restoreKeroseneFeeAfterReorg(zero);
        assertThat(fees.profitSegregationMode()).isEqualTo("SUBLEDGER");
        assertThat(fees.profitReconcileWithVault()).isTrue();
        verify(mockStore, never()).admit(anyString());
        verifyNoInteractions(movements, wallets, balances, audit, metricsProvider, metrics);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void activeMovementFieldsAndFinancialOrderingAreUnchanged(Root root) {
        activeRows(root);
        invoke(root);

        ArgumentCaptor<KfeBalanceMovementEntity> row = ArgumentCaptor.forClass(KfeBalanceMovementEntity.class);
        verify(movements).save(row.capture());
        assertThat(row.getValue().getTransactionId()).isEqualTo(tx.getId());
        assertThat(row.getValue().getWalletId()).isEqualTo(profitWalletId);
        assertThat(row.getValue().getMovementType()).isEqualTo(root.movementType);
        assertThat(row.getValue().getAmountSats()).isEqualTo(900L);
        assertThat(row.getValue().getFromBucket()).isEqualTo(root.fromBucket);
        assertThat(row.getValue().getToBucket()).isEqualTo(root.toBucket);
        assertThat(row.getValue().getAsset()).isEqualTo("BTC");

        var order = inOrder(mockStore, movements, balances, audit);
        order.verify(mockStore).admit(root.operation);
        order.verify(movements).save(any(KfeBalanceMovementEntity.class));
        switch (root) {
            case CREDIT -> {
                order.verify(balances).creditAvailable(profitWalletId, KfeSystemWalletService.ASSET_BTC, 900L);
                order.verify(audit).record("KFE_KEROSENE_FEE_SETTLED", tx.getId(), profitWalletId,
                        null, tx.getStatus(), Map.of(
                                "transactionId", tx.getId().toString(),
                                "profitWalletId", profitWalletId.toString(),
                                "keroseneFeeSats", 900L,
                                "segregationMode", "SUBLEDGER", "reconcileWithVault", "true"));
            }
            case REVERSE -> {
                order.verify(balances).reverseAvailableCreditForReorg(
                        profitWalletId, KfeSystemWalletService.ASSET_BTC, 900L);
                order.verify(audit).record("KFE_KEROSENE_FEE_REORG_REVERSED", tx.getId(), profitWalletId,
                        tx.getStatus(), tx.getStatus(), Map.of(
                                "feeSats", 900L, "debitedSats", 600L, "debtAddedSats", 300L));
                verify(balances, never()).creditAvailable(any(), anyString(), anyLong());
            }
            case RESTORE -> {
                order.verify(balances).creditAvailable(profitWalletId, KfeSystemWalletService.ASSET_BTC, 900L);
                verifyNoInteractions(audit);
            }
            case MOVEMENT -> verifyNoInteractions(wallets, balances, audit);
        }
        verify(mockStore, times(1)).admit(anyString());
        verify(mockStore).resolve(admission.id(), false);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void existingMovementKeepsIdempotentSkipAndUncertainty(Root root) {
        activeRows(root);
        when(movements.existsByTransactionIdAndMovementType(tx.getId(), root.movementType)).thenReturn(true);
        if (root == Root.MOVEMENT) {
            assertThat(invoke(root)).isFalse();
        } else {
            invoke(root);
        }
        verify(movements, never()).save(any());
        verifyNoInteractions(wallets, balances, audit);
        if (root == Root.CREDIT) {
            verify(metrics).recordFeeIdempotentSkip();
        }
        verify(mockStore).resolve(admission.id(), false);
    }

    @ParameterizedTest
    @EnumSource(value = Root.class, names = {"REVERSE", "RESTORE"})
    void missingPrerequisiteMovementSkipsAfterAdmission(Root root) {
        invoke(root);
        verify(movements, never()).save(any());
        verifyNoInteractions(wallets, balances, audit);
        verify(mockStore).admit(root.operation);
        verify(mockStore).resolve(admission.id(), false);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void caughtUniqueRaceDoesNotCreditOrCertifyCommittedWorkflow(Root root) {
        activeRows(root);
        when(movements.save(any())).thenThrow(new DataIntegrityViolationException("synthetic duplicate"));
        beginTransaction();
        guard.executeMutation("synthetic.parent", () -> {
            drain();
            if (root == Root.MOVEMENT) {
                assertThat(invoke(root)).isFalse();
            } else {
                assertThatCode(() -> invoke(root)).doesNotThrowAnyException();
            }
            return true;
        });
        verifyNoInteractions(balances, audit);
        verify(mockStore, times(1)).admit(anyString());
        if (root == Root.CREDIT) {
            verify(metrics).recordFeeIdempotentSkip();
        }
        verify(mockStore, never()).resolve(any(), anyBoolean());
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(mockStore).resolve(admission.id(), false);
        verify(mockStore, never()).resolve(any(), eq(true));
    }

    static Stream<Arguments> completions() {
        return Arrays.stream(Root.values()).flatMap(root ->
                Arrays.stream(Completion.values()).map(completion -> Arguments.of(root, completion)));
    }

    @ParameterizedTest
    @MethodSource("completions")
    void everySuccessfulOutcomeRemainsUncertain(Root root, Completion completion) {
        activeRows(root);
        if (completion != Completion.DIRECT) {
            beginTransaction();
        }
        invoke(root);
        if (completion != Completion.DIRECT) {
            verify(mockStore, never()).resolve(any(), anyBoolean());
            finishTransaction(switch (completion) {
                case COMMIT -> TransactionSynchronization.STATUS_COMMITTED;
                case ROLLBACK -> TransactionSynchronization.STATUS_ROLLED_BACK;
                case UNKNOWN -> TransactionSynchronization.STATUS_UNKNOWN;
                case DIRECT -> throw new AssertionError("direct invocation has no transaction");
            });
        }
        verify(mockStore).resolve(admission.id(), false);
        verify(mockStore, never()).resolve(any(), eq(true));
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void alreadyAdmittedParentCanFinishNestedWorkAfterDrain(Root root) {
        activeRows(root);
        beginTransaction();
        guard.executeMutation("synthetic.parent", () -> {
            drain();
            invoke(root);
            return true;
        });
        verify(movements).save(any());
        verify(mockStore, times(1)).admit(anyString());
        verify(mockStore, never()).resolve(any(), anyBoolean());
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(mockStore).resolve(admission.id(), false);
        clearInvocations(movements, wallets, balances, audit, metricsProvider, metrics);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(movements, wallets, balances, audit, metricsProvider, metrics);
    }

    @Test
    void caughtNonIdempotentFailureCannotClearParentAdmission() {
        when(movements.save(any())).thenThrow(new DataIntegrityViolationException("synthetic failure"));
        beginTransaction();
        guard.executeMutation("synthetic.parent", () -> {
            drain();
            assertThatThrownBy(() -> recorder.record(tx.getId(), profitWalletId, "DEBIT", -900L,
                    "AVAILABLE", "LOCKED")).isInstanceOf(DataIntegrityViolationException.class);
            return true;
        });
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(mockStore, times(1)).admit(anyString());
        verify(mockStore).resolve(admission.id(), false);
    }

    @Test
    void recorderPreservesNullTransactionZeroAndSignedNonIdempotentWrites() {
        assertThat(recorder.record(null, profitWalletId, "DEBIT", 0L, "AVAILABLE", "LOCKED")).isTrue();
        assertThat(recorder.record(tx.getId(), profitWalletId, "DEBIT", -900L, "AVAILABLE", "LOCKED")).isTrue();
        ArgumentCaptor<KfeBalanceMovementEntity> rows = ArgumentCaptor.forClass(KfeBalanceMovementEntity.class);
        verify(movements, times(2)).save(rows.capture());
        assertThat(rows.getAllValues().get(0).getTransactionId()).isNull();
        assertThat(rows.getAllValues().get(0).getAmountSats()).isZero();
        assertThat(rows.getAllValues().get(1).getAmountSats()).isEqualTo(-900L);
        verify(movements, never()).existsByTransactionIdAndMovementType(any(), any());
        verify(mockStore, times(2)).resolve(admission.id(), false);
        drain();
        clearInvocations(movements);
        assertThatThrownBy(() -> recorder.record(null, profitWalletId, "DEBIT", 0L, "AVAILABLE", "LOCKED"))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(movements);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void transactionWithoutCompletionObservationRejectsBeforeEffects(Root root) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(movements, wallets, balances, audit, metricsProvider, metrics);
        verify(mockStore, never()).resolve(any(), anyBoolean());
    }

    private KfeFeeSettlementService feeService() {
        return new KfeFeeSettlementService(wallets, balances, recorder, movements, audit,
                metricsProvider, " subledger ", true);
    }

    private void activeRows(Root root) {
        lenient().when(wallets.requireProfitWalletId()).thenReturn(profitWalletId);
        lenient().when(movements.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(movements.existsByTransactionIdAndMovementType(
                tx.getId(), KfeLedgerMovementTypes.CREDIT_KEROSENE_FEE)).thenReturn(root == Root.REVERSE);
        lenient().when(movements.existsByTransactionIdAndMovementType(
                tx.getId(), KfeLedgerMovementTypes.REVERSAL_KEROSENE_FEE)).thenReturn(root == Root.RESTORE);
        // Reverse requires the credit row; restore requires the reversal row.
        lenient().when(balances.reverseAvailableCreditForReorg(profitWalletId, KfeSystemWalletService.ASSET_BTC, 900L))
                .thenReturn(new KfeBalanceService.ReorgDebitResult(600L, 300L, 300L));
        lenient().when(metricsProvider.getIfAvailable()).thenReturn(metrics);
    }

    private Boolean invoke(Root root) {
        switch (root) {
            case CREDIT -> fees.creditKeroseneFee(tx);
            case REVERSE -> fees.reverseKeroseneFeeForReorg(tx);
            case RESTORE -> fees.restoreKeroseneFeeAfterReorg(tx);
            case MOVEMENT -> {
                return recorder.record(tx.getId(), profitWalletId, root.movementType, 900L,
                        root.fromBucket, root.toBucket);
            }
        }
        return null;
    }

    private void drain() {
        guard.requestDrain(new KfeMaintenanceGuard.Command("synthetic-fee-movement", "bounded test", 0), 7L);
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void finishTransaction(int completion) {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clear();
        callbacks.forEach(callback -> callback.afterCompletion(completion));
    }
}
