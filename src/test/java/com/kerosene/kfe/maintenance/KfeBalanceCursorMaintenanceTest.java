package com.kerosene.kfe.maintenance;

import com.kerosene.kfe.model.KfeBalanceEntity;
import com.kerosene.kfe.model.KfeDerivationCursorEntity;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.repository.KfeBalanceRepository;
import com.kerosene.kfe.repository.KfeDerivationCursorRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.service.BalanceEventPublisher;
import com.kerosene.kfe.service.KfeBalanceService;
import com.kerosene.kfe.service.KfeDerivationCursorService;
import com.kerosene.kfe.service.KfeHashService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeBalanceCursorMaintenanceTest {
    private enum Root {
        CREATE, LOCK, RESERVE, SETTLE, RELEASE, CREDIT, REORG,
        OBSERVED, OBSERVED_META, CREDIT_OBSERVED, ZERO, CURSOR
    }

    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final KfeBalanceRepository rows = mock(KfeBalanceRepository.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeHashService hashes = spy(new KfeHashService());
    private final BalanceEventPublisher publisher = mock(BalanceEventPublisher.class);
    private final KfeDerivationCursorRepository cursorRows = mock(KfeDerivationCursorRepository.class);
    private KfeBalanceService balances = new KfeBalanceService(rows, hashes, wallets, publisher);
    private KfeDerivationCursorService cursors = new KfeDerivationCursorService(cursorRows);
    private final UUID walletId = UUID.randomUUID();
    private final KfeBalanceEntity balance = KfeBalanceEntity.empty(walletId, "BTC", "synthetic-genesis");
    private final KfeDerivationCursorEntity cursor = new KfeDerivationCursorEntity();

    @BeforeEach
    void setup() {
        balances.setMaintenanceGuard(guard);
        cursors.setMaintenanceGuard(guard);
        lenient().when(store.admit(anyString())).thenReturn(admission);
        balance.setAvailableSats(100);
        balance.setLockedSats(40);
        cursor.setCursorKey("synthetic-cursor");
        cursor.setLastIssuedIndex(7);
    }

    @AfterEach
    void clearThreadTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @ParameterizedTest @EnumSource(Root.class)
    void drainRejectsBeforeLockHashOrManagedEntityMutation(Root root) {
        doThrow(new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain"))
                .when(store).admit(anyString());
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        unchangedAndNoEffects();
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest @EnumSource(Root.class)
    void unavailableDefaultCannotStartEffects(Root root) {
        // Exercise actual constructor defaults, not a test-installed rejection stub.
        balances = new KfeBalanceService(rows, hashes, wallets, publisher);
        cursors = new KfeDerivationCursorService(cursorRows);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        unchangedAndNoEffects(); verifyNoInteractions(store);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void storageFailureIsFailClosedBeforeEffects(Root root) {
        doThrow(new IllegalStateException("synthetic outage")).when(store).admit(anyString());
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        unchangedAndNoEffects();
    }

    @ParameterizedTest @EnumSource(value = Root.class, names = {"CREATE", "CURSOR"})
    void directReturnWithoutObservableTransactionRemainsUncertain(Root root) {
        activeRows();
        invoke(root);
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @ParameterizedTest @EnumSource(value = Root.class, names = {"CREATE", "CURSOR"})
    void localCommitCompletionIsDeferredUntilObservedCommit(Root root) {
        activeRows(); beginTransaction();
        invoke(root);
        verify(store, never()).resolve(any(), anyBoolean());
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), true);
    }

    @ParameterizedTest @EnumSource(value = Root.class, names = {"CREATE", "CURSOR"})
    void rollbackCannotManufactureCertainCompletion(Root root) {
        activeRows(); beginTransaction(); invoke(root);
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest @EnumSource(value = Root.class, names = {"CREATE", "CURSOR"})
    void unobservableTransactionCannotStartLocalEffects(Root root) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        unchangedAndNoEffects();
    }

    @Test
    void nestedLockAndWriteReuseAdmissionEvenAfterDrainStarts() {
        activeRows();
        guard.executeMutation("synthetic.parent", () -> {
            doThrow(new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain"))
                    .when(store).admit(anyString());
            assertThat(balances.reserve(walletId, "BTC", 10)).isSameAs(balance);
            return null;
        });
        assertThat(balance.getAvailableSats()).isEqualTo(90);
        assertThat(balance.getLockedSats()).isEqualTo(50);
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        assertThatThrownBy(() -> balances.reserve(walletId, "BTC", 10))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        assertThat(balance.getAvailableSats()).isEqualTo(90);
    }

    @Test
    void creditStillPaysReorgDebtBeforeAvailableAndSignsExactResult() {
        activeRows(); balance.setReorgDebtSats(12);
        balances.creditAvailable(walletId, "BTC", 20);
        assertThat(balance.getAvailableSats()).isEqualTo(108);
        assertThat(balance.getReorgDebtSats()).isZero();
        assertThat(balance.getNonce()).isEqualTo(1);
        assertThat(balance.getLastHash()).isEqualTo(new KfeHashService().balanceHash(balance));
        assertThat(balance.getBalanceSignature()).isEqualTo(balance.getLastHash());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void reorgAndAbsoluteObservedMetadataPreserveExistingAlgorithms() {
        activeRows();
        assertThat(balances.reverseAvailableCreditForReorg(walletId, "BTC", 150))
                .isEqualTo(new KfeBalanceService.ReorgDebitResult(100, 50, 50));
        balances.setObserved(walletId, "BTC", 200, " LIVE_MEMPOOL_AWARE ", " synthetic ");
        assertThat(balance.getObservedSats()).isEqualTo(200);
        assertThat(balance.getObservedProbeQuality()).isEqualTo("LIVE_MEMPOOL_AWARE");
        assertThat(balance.getObservedProbeSource()).isEqualTo("synthetic");
        assertThat(balance.getObservedProbeAt()).isNotNull();
        assertThat(balance.getReorgDebtSats()).isEqualTo(50);
    }

    @Test
    void swallowedPublicationFailureDoesNotClearCommittedFinancialAdmission() {
        activeRows(); KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(walletId); wallet.setUserId(7L); wallet.setLabel("synthetic");
        when(wallets.findById(walletId)).thenReturn(Optional.of(wallet));
        doThrow(new IllegalStateException("synthetic delivery failure"))
                .when(publisher).publishBalanceUpdateAfterCommit(any());
        beginTransaction(); balances.creditAvailable(walletId, "BTC", 5);
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(balance.getAvailableSats()).isEqualTo(105);
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @Test
    void lockedMutableHandleIsNotLocalCompletionProof() {
        activeRows(); beginTransaction();
        assertThat(balances.requireForUpdate(walletId, "BTC")).isSameAs(balance);
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void initialCursorStillStartsAtZeroAndExistingCursorAdvancesOnce() {
        when(cursorRows.findByCursorKeyForUpdate(anyString())).thenReturn(Optional.empty(), Optional.of(cursor));
        assertThat(cursors.nextIndex("synthetic-new")).isZero();
        assertThat(cursors.nextIndex("synthetic-cursor")).isEqualTo(8);
        verify(cursorRows, times(2)).save(any());
        verify(store, times(2)).resolve(admission.id(), false);
    }

    @Test
    void pureInvalidObservedInputHasNoAdmissionOrEffects() {
        assertThatThrownBy(() -> balances.creditObserved(walletId, "BTC", 0))
                .isInstanceOf(IllegalArgumentException.class);
        unchangedAndNoEffects(); verifyNoInteractions(store);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void activeRootsRetainTheirPublicContractsWithoutTransactionInvention(Root root) {
        activeRows(); invoke(root);
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        switch (root) {
            case RESERVE -> {
                assertThat(balance.getAvailableSats()).isEqualTo(90);
                assertThat(balance.getLockedSats()).isEqualTo(50);
            }
            case SETTLE -> assertThat(balance.getLockedSats()).isEqualTo(30);
            case RELEASE -> {
                assertThat(balance.getAvailableSats()).isEqualTo(110);
                assertThat(balance.getLockedSats()).isEqualTo(30);
            }
            case CREDIT -> assertThat(balance.getAvailableSats()).isEqualTo(110);
            case REORG -> assertThat(balance.getAvailableSats()).isEqualTo(90);
            case OBSERVED, OBSERVED_META, CREDIT_OBSERVED -> assertThat(balance.getObservedSats()).isEqualTo(10);
            case ZERO -> {
                assertThat(balance.getAvailableSats()).isZero();
                assertThat(balance.getLockedSats()).isZero();
            }
            case CURSOR -> assertThat(cursor.getLastIssuedIndex()).isEqualTo(8);
            case LOCK -> verify(rows, never()).save(any());
            case CREATE -> verify(rows).save(argThat(value -> value.getId().getAsset().equals("BTC")
                    && value.getAvailableSats() == 0 && value.getNonce() == 0));
        }
    }

    private void activeRows() {
        lenient().when(rows.findByWalletIdAndAssetForUpdate(walletId, "BTC")).thenReturn(Optional.of(balance));
        lenient().when(rows.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(cursorRows.findByCursorKeyForUpdate(anyString())).thenReturn(Optional.of(cursor));
        lenient().when(cursorRows.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void invoke(Root root) {
        switch (root) {
            case CREATE -> balances.createEmptyBalance(walletId, null);
            case LOCK -> balances.requireForUpdate(walletId, null);
            case RESERVE -> balances.reserve(walletId, "BTC", 10);
            case SETTLE -> balances.settleReservedDebit(walletId, "BTC", 10);
            case RELEASE -> balances.releaseReserved(walletId, "BTC", 10);
            case CREDIT -> balances.creditAvailable(walletId, "BTC", 10);
            case REORG -> balances.reverseAvailableCreditForReorg(walletId, "BTC", 10);
            case OBSERVED -> balances.setObserved(walletId, "BTC", 10);
            case OBSERVED_META -> balances.setObserved(walletId, "BTC", 10, "LIVE", "synthetic");
            case CREDIT_OBSERVED -> balances.creditObserved(walletId, "BTC", 10);
            case ZERO -> balances.zeroSpendableBucketsIfNeeded(walletId, "BTC");
            case CURSOR -> cursors.nextIndex("synthetic-cursor");
        }
    }

    private void unchangedAndNoEffects() {
        verifyNoInteractions(rows, hashes, wallets, publisher, cursorRows);
        assertThat(balance.getAvailableSats()).isEqualTo(100);
        assertThat(balance.getLockedSats()).isEqualTo(40);
        assertThat(balance.getNonce()).isZero();
        assertThat(cursor.getLastIssuedIndex()).isEqualTo(7);
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void finishTransaction(int state) {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clear();
        callbacks.forEach(callback -> callback.afterCompletion(state));
    }
}
