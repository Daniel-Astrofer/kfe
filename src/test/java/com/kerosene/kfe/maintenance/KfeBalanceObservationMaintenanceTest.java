package com.kerosene.kfe.maintenance;

import com.kerosene.kfe.model.KfeBalanceEntity;
import com.kerosene.kfe.model.KfeWalletAddressEntity;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.model.KfeWalletStatus;
import com.kerosene.kfe.rail.BlockchainClient;
import com.kerosene.kfe.repository.KfeWalletAddressRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.service.ChainProbeResult;
import com.kerosene.kfe.service.KfeBalanceMetrics;
import com.kerosene.kfe.service.KfeBalanceService;
import com.kerosene.kfe.service.KfeDashboardPublisher;
import com.kerosene.kfe.service.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.service.KfeWalletDescriptorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real admission and Spring completion lifecycle; storage, RPC and financial boundaries are mocked. */
class KfeBalanceObservationMaintenanceTest {
    private enum Root { SYNC, APPLY, LEGACY_APPLY, SCHEDULED }

    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicLong uncertain = new AtomicLong();
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addresses = mock(KfeWalletAddressRepository.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final ObjectProvider<BlockchainClient> chains = provider();
    private final BlockchainClient chain = mock(BlockchainClient.class);
    private final KfeDashboardPublisher dashboard = mock(KfeDashboardPublisher.class);
    private final KfeWalletDescriptorResolver descriptors = mock(KfeWalletDescriptorResolver.class);
    private final ObjectProvider<KfeBalanceMetrics> metrics = provider();
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final TransactionTemplate template = new TransactionTemplate(transactions);
    private final KfeWalletEntity wallet = new KfeWalletEntity();
    private final KfeBalanceEntity balance = new KfeBalanceEntity();
    private KfeOnchainBalanceSyncService service;

    @BeforeEach
    void explicitActiveAdmissionFixture() {
        when(store.admit(anyString())).thenAnswer(invocation -> {
            if (draining.get()) {
                throw new KfeMaintenanceGuard.MaintenanceException(503, "KFE is draining.");
            }
            return admission;
        });
        doAnswer(invocation -> {
            if (!invocation.<Boolean>getArgument(1)) {
                uncertain.incrementAndGet();
            }
            return null;
        }).when(store).resolve(any(), anyBoolean());
        when(store.observe()).thenAnswer(invocation -> new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(draining.get()
                        ? KfeMaintenanceGuard.Mode.DRAINING : KfeMaintenanceGuard.Mode.ACTIVE,
                        "balance-wave", 0), Instant.now(), Map.of("admissionsUncertain", uncertain.get())));
        wallet.setId(UUID.randomUUID());
        wallet.setUserId(7L);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        balance.setObservedSats(40_000L);
        service = newService(2);
        service.setMaintenanceGuard(guard);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void drainingRejectsBeforeAnyRepositoryProviderRpcOrWrite(Root root) {
        draining.set(true);
        if (root == Root.SCHEDULED) {
            assertThatCode(() -> call(root, service)).doesNotThrowAnyException();
        } else {
            assertUnavailable(() -> call(root, service));
        }
        assertNoEffects();
        verify(store).admit(operation(root));
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void constructionWithoutInjectionFailsClosedAndSchedulerPauses(Root root) {
        KfeOnchainBalanceSyncService unavailable = newService(2);
        if (root == Root.SCHEDULED) {
            assertThatCode(() -> call(root, unavailable)).doesNotThrowAnyException();
        } else {
            assertUnavailable(() -> call(root, unavailable));
        }
        assertNoEffects();
        verifyNoInteractions(store);
    }

    @Test
    void setterIsRequiredAndNullCannotReplaceGuard() throws Exception {
        var annotation = KfeOnchainBalanceSyncService.class
                .getMethod("setMaintenanceGuard", KfeMaintenanceGuard.class).getAnnotation(Autowired.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.required()).isTrue();
        assertThatThrownBy(() -> service.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void unavailableAdmissionStorageHasNoEffectsAndSchedulerPauses(Root root) {
        when(store.admit(anyString())).thenThrow(new IllegalStateException("storage unavailable"));
        if (root == Root.SCHEDULED) {
            assertThatCode(() -> call(root, service)).doesNotThrowAnyException();
        } else {
            assertUnavailable(() -> call(root, service));
        }
        assertNoEffects();
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @Test
    void admittedRemoteProbeFinishesAfterDrainWithOneRootAndRetainsUncertainty() {
        prepareSync();
        when(chain.getConfirmedBalanceForDescriptor("wpkh(tpub-fixture/0/*)", 1000))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    draining.set(true);
                    return 50_000L;
                });
        when(chain.getConfirmedBalanceForDescriptor("wpkh(tpub-fixture/1/*)", 1000)).thenReturn(10_000L);
        assertThat(service.syncWallet(wallet.getId())).isEqualTo(60_000L);
        verify(store).admit("balance-observation.sync-wallet");
        verify(store, times(1)).admit(anyString());
        verify(balances).setObserved(wallet.getId(), "BTC", 60_000L,
                "CONFIRMED_UTXO_SET", "syncWallet-descriptor");
        verify(dashboard).publishAfterCommit(7L);
        verify(store).resolve(admission.id(), false);
        assertUnavailable(() -> service.applyObserved(wallet.getId(), liveProbe()));
        verify(store, times(2)).admit(anyString());
        assertThat(guard.status().blockers()).containsEntry("admissionsUncertain", 1L);
    }

    @Test
    void swallowedOuterProbeFailureKeepsPreviousBalanceAndUncertainAdmission() {
        prepareSync();
        when(chain.getUnspentBalanceForAddresses(List.of("bcrt1qfixture")))
                .thenThrow(new IllegalStateException("RPC outcome unknown"));
        assertThat(service.syncWallet(wallet.getId())).isEqualTo(-1L);
        verifyNoInteractions(balances, dashboard, metrics, descriptors);
        assertThat(transactions.commits).isZero();
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void swallowedDescriptorFailurePreservesAddressMaximumButCannotCompleteRoot() {
        prepareSync();
        when(chain.getUnspentBalanceForAddresses(List.of("bcrt1qfixture"))).thenReturn(50_000L);
        when(chain.getConfirmedBalanceForDescriptor(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("descriptor scan interrupted"));
        assertThat(service.syncWallet(wallet.getId())).isEqualTo(50_000L);
        verify(balances).setObserved(wallet.getId(), "BTC", 50_000L,
                "CONFIRMED_UTXO_SET", "syncWallet-descriptor");
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void swallowedAddressScanFailureStillUsesExistingMaximumAndStaysUncertain() {
        prepareSync();
        when(chain.getUnspentBalanceForAddresses(List.of("bcrt1qfixture"))).thenReturn(50_000L);
        when(chain.getConfirmedBalanceForAddress("bcrt1qfixture"))
                .thenThrow(new IllegalStateException("address scan interrupted"));
        assertThat(service.syncWallet(wallet.getId())).isEqualTo(50_000L);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void schedulerKeepsBatchBoundAndSwallowedWalletFailureCannotCompleteRoot() {
        prepareSync();
        KfeWalletEntity missing = new KfeWalletEntity();
        missing.setId(UUID.randomUUID());
        KfeWalletEntity outsideBatch = new KfeWalletEntity();
        outsideBatch.setId(UUID.randomUUID());
        when(wallets.findByKindInAndStatus(List.of(KfeWalletKind.CUSTODIAL_ONCHAIN), KfeWalletStatus.ACTIVE))
                .thenReturn(List.of(missing, wallet, outsideBatch));
        service.reconcileActiveOnchainWallets();
        verify(wallets).findById(missing.getId());
        verify(balances).setObserved(wallet.getId(), "BTC", 0L,
                "CONFIRMED_UTXO_SET", "syncWallet-descriptor");
        verify(wallets, never()).findById(outsideBatch.getId());
        verify(store).admit("balance-observation.reconcile");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void coldWritePreservesMetadataZeroSpendableAndCallbackUncertainty() {
        prepareApply();
        wallet.setKind(KfeWalletKind.WATCH_ONLY);
        assertThat(service.applyObserved(wallet.getId(), liveProbe())).isEqualTo(50_000L);
        verify(balances).setObserved(wallet.getId(), "BTC", 50_000L,
                "LIVE_MEMPOOL_AWARE", "maintenance-fixture");
        verify(balances).zeroSpendableBucketsIfNeeded(wallet.getId(), "BTC");
        verify(dashboard).publishAfterCommit(7L);
        assertThat(transactions.commits).isEqualTo(1);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void qualityDeferStillPreservesNonzeroColdWithoutPublishing() {
        prepareApply();
        wallet.setKind(KfeWalletKind.WATCH_ONLY);
        assertThat(service.applyObserved(wallet.getId(), 60_000L)).isEqualTo(40_000L);
        verify(balances, never()).setObserved(any(), anyString(), anyLong(), anyString(), anyString());
        verify(balances, never()).zeroSpendableBucketsIfNeeded(any(), anyString());
        verifyNoInteractions(dashboard);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void invalidObservationsRemainEffectFreeDuringDrain() {
        draining.set(true);
        assertThat(service.applyObserved(wallet.getId(), -1L)).isEqualTo(-1L);
        assertThat(service.applyObserved(wallet.getId(), (ChainProbeResult) null)).isEqualTo(-1L);
        assertThat(service.applyObserved(wallet.getId(), ChainProbeResult.unknown("failed"))).isEqualTo(-1L);
        assertNoEffects();
        verifyNoInteractions(store);
    }

    @Test
    void nestedSynchronousApplyUsesParentAdmissionDuringDrainButCannotCompleteIt() {
        prepareApply();
        long result = guard.executeMutation("parent.observation", () -> {
            draining.set(true);
            return service.applyObserved(wallet.getId(), liveProbe());
        });
        assertThat(result).isEqualTo(50_000L);
        verify(store).admit("parent.observation");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void springCommitDefersResolutionAndDoesNotProveAfterCommitDelivery() {
        prepareApply();
        AtomicBoolean published = new AtomicBoolean();
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { published.set(true); }
            });
            return null;
        }).when(dashboard).publishAfterCommit(7L);
        template.executeWithoutResult(status -> {
            assertThat(service.applyObserved(wallet.getId(), liveProbe())).isEqualTo(50_000L);
            verify(store, never()).resolve(any(), anyBoolean());
            assertThat(published).isFalse();
        });
        assertThat(published).isTrue();
        assertThat(transactions.commits).isEqualTo(1);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void springRollbackDoesNotManufactureCompletion() {
        prepareApply();
        template.executeWithoutResult(status -> {
            service.applyObserved(wallet.getId(), liveProbe());
            verify(store, never()).resolve(any(), anyBoolean());
            status.setRollbackOnly();
        });
        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isEqualTo(1);
        verify(store).resolve(admission.id(), false);
        // Mock balances do not emulate database rollback; this verifies Spring synchronization.
    }

    @Test
    void afterCommitFailureRetainsUncertaintyEvenThoughBalanceTransactionCommitted() {
        prepareApply();
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    throw new IllegalStateException("delivery outcome unknown");
                }
            });
            return null;
        }).when(dashboard).publishAfterCommit(7L);
        assertThatThrownBy(() -> service.applyObserved(wallet.getId(), liveProbe()))
                .hasMessage("delivery outcome unknown");
        assertThat(transactions.commits).isEqualTo(1);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void balanceWriteFailureRetainsUncertaintyAndDoesNotPublish() {
        prepareApply();
        doThrow(new IllegalStateException("write failed")).when(balances)
                .setObserved(wallet.getId(), "BTC", 50_000L, "LIVE_MEMPOOL_AWARE", "maintenance-fixture");
        assertThatThrownBy(() -> service.applyObserved(wallet.getId(), liveProbe())).hasMessage("write failed");
        assertThat(transactions.rollbacks).isEqualTo(1);
        verifyNoInteractions(dashboard);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void partialObservationCoverageNeverClearsUnknownBlockers() {
        draining.set(true);
        var status = guard.status();
        assertThat(status.safeToUpdate()).isFalse();
        assertThat(status.blockers()).containsEntry("mutationCoverageUnknown", 1L)
                .containsEntry("callbackCoverageUnknown", 1L).containsEntry("readSideEffectsUnknown", 1L);
    }

    private void prepareApply() {
        when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        when(balances.requireForUpdate(wallet.getId(), "BTC")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return balance;
        });
    }

    private void prepareSync() {
        prepareApply();
        when(chains.getIfAvailable()).thenReturn(chain);
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setAddress(" bcrt1qfixture ");
        when(addresses.findByWalletIdOrderByCreatedAtDesc(wallet.getId())).thenReturn(List.of(address));
        when(descriptors.resolveReceiveDescriptor(wallet)).thenReturn("wpkh(tpub-fixture/0/*)");
    }

    private ChainProbeResult liveProbe() {
        return ChainProbeResult.liveMempoolAware(50_000L, 2, "maintenance-fixture");
    }

    private void call(Root root, KfeOnchainBalanceSyncService target) {
        switch (root) {
            case SYNC -> target.syncWallet(wallet.getId());
            case APPLY -> target.applyObserved(wallet.getId(), liveProbe());
            case LEGACY_APPLY -> target.applyObserved(wallet.getId(), 50_000L);
            case SCHEDULED -> target.reconcileActiveOnchainWallets();
        }
    }

    private String operation(Root root) {
        return switch (root) {
            case SYNC -> "balance-observation.sync-wallet";
            case APPLY, LEGACY_APPLY -> "balance-observation.apply-observed";
            case SCHEDULED -> "balance-observation.reconcile";
        };
    }

    private void assertUnavailable(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(KfeMaintenanceGuard.MaintenanceException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(503));
    }

    private void assertNoEffects() {
        verifyNoInteractions(wallets, addresses, balances, chains, chain, dashboard, descriptors, metrics);
        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isZero();
    }

    private KfeOnchainBalanceSyncService newService(int batchSize) {
        return new KfeOnchainBalanceSyncService(wallets, addresses, balances, chains, dashboard,
                template, descriptors, metrics, batchSize, 1000, 120);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() { return mock(ObjectProvider.class); }

    private static final class RecordingTransactions extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
