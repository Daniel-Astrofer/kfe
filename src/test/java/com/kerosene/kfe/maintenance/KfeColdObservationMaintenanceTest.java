package com.kerosene.kfe.maintenance;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.kerosene.common.financial.FinancialNotificationPort;
import com.kerosene.kfe.config.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.model.KfeWalletAddressEntity;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.model.KfeWalletStatus;
import com.kerosene.kfe.rail.BlockchainClient;
import com.kerosene.kfe.repository.KfeBalanceRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.repository.KfeWalletAddressRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.service.ChainProbeResult;
import com.kerosene.kfe.service.KfeBitcoinZmqTxMatcher;
import com.kerosene.kfe.service.KfeColdWalletObservationService;
import com.kerosene.kfe.service.KfeDashboardPublisher;
import com.kerosene.kfe.service.KfeMonitoredChainAddressIndex;
import com.kerosene.kfe.service.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.service.KfeStatementService;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real guard and Spring completion lifecycle; storage, chain and financial boundaries are mocked. */
class KfeColdObservationMaintenanceTest {
    private enum Root { OBSERVE, ZMQ, REFRESH, PSBT, TOUCH, SCHEDULED }

    private static final String RECEIVE = "wpkh(tpub-fixture/0/*)";
    private static final String CHANGE = "wpkh(tpub-fixture/1/*)";
    private static final String ADDRESS = "bcrt1qcoldfixture";
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicLong uncertain = new AtomicLong();
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addresses = mock(KfeWalletAddressRepository.class);
    private final KfeTransactionRepository history = mock(KfeTransactionRepository.class);
    private final KfeBalanceRepository balances = mock(KfeBalanceRepository.class);
    private final ObjectProvider<BlockchainClient> chains = provider();
    private final BlockchainClient chain = mock(BlockchainClient.class);
    private final KfeStatementService statements = mock(KfeStatementService.class);
    private final KfeDashboardPublisher dashboard = mock(KfeDashboardPublisher.class);
    private final ObjectProvider<KfeOnchainBalanceSyncService> syncs = provider();
    private final KfeOnchainBalanceSyncService sync = mock(KfeOnchainBalanceSyncService.class);
    private final KfeWalletDescriptorResolver descriptors = mock(KfeWalletDescriptorResolver.class);
    private final ObjectProvider<KfeMonitoredChainAddressIndex> indexes = provider();
    private final KfeMonitoredChainAddressIndex index = mock(KfeMonitoredChainAddressIndex.class);
    private final ObjectProvider<FinancialNotificationPort> notifications = provider();
    private final FinancialNotificationPort notification = mock(FinancialNotificationPort.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final TransactionTemplate template = new TransactionTemplate(transactions);
    private final KfeWalletEntity wallet = new KfeWalletEntity();
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final UUID workflowId = UUID.randomUUID();
    private KfeColdWalletObservationService service;

    @BeforeEach
    void explicitActiveStoreAndRealGuard() {
        when(store.admit(anyString())).thenAnswer(invocation -> {
            if (draining.get()) {
                throw new KfeMaintenanceGuard.MaintenanceException(503, "KFE is draining.");
            }
            return new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
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
                        "cold-observation-fixture", 0), Instant.now(),
                Map.of("admissionsUncertain", uncertain.get())));
        wallet.setId(UUID.randomUUID());
        wallet.setUserId(7L);
        wallet.setKind(KfeWalletKind.WATCH_ONLY);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        tx.setUserId(7L);
        tx.setSourceWalletId(wallet.getId());
        tx.setProvider(KfeColdWalletObservationService.PROVIDER_COLD_PSBT);
        tx.setBlockchainTxid("fixture-spend");
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setConfirmations(1);
        service = newService();
        service.setMaintenanceGuard(guard);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void drainingRejectsEvenKnownWalletTransactionOrPsbtIdsBeforeEffects(Root root) {
        prepare();
        // Existing history/PSBT rows do not confer maintenance provenance.
        when(history.findByIdempotencyKey("cold-psbt:" + workflowId)).thenReturn(Optional.of(tx));
        draining.set(true);
        assertRejectedOrPaused(root, service);
        assertNoEffects();
        verify(store).admit(operation(root));
        verify(store, never()).resolve(any(), anyBoolean());
        assertThat(tx.getConfirmations()).isEqualTo(1);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void storageOutageRejectsBeforeEffects(Root root) {
        prepare();
        when(store.admit(anyString())).thenThrow(new IllegalStateException("storage unavailable"));
        assertRejectedOrPaused(root, service);
        assertNoEffects();
        verify(store).admit(operation(root));
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void uninjectedConstructionStaysUnavailable(Root root) {
        prepare();
        assertRejectedOrPaused(root, newService());
        assertNoEffects();
        verifyNoInteractions(store);
    }

    @Test
    void setterRequiresRealInjectionAndRejectsNull() throws Exception {
        Autowired annotation = KfeColdWalletObservationService.class
                .getMethod("setMaintenanceGuard", KfeMaintenanceGuard.class).getAnnotation(Autowired.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.required()).isTrue();
        assertThatThrownBy(() -> service.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
        prepare();
        draining.set(true);
        assertUnavailable(() -> service.touchColdConfirmations(tx.getId(), 4));
        assertNoEffects();
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void currentAdmittedParentCanFinishEveryNestedRootDuringDrain(Root root) {
        prepare();
        guard.executeMutation("parent.cold-observation", () -> {
            draining.set(true);
            call(root, service);
            return null;
        });
        verify(store).admit("parent.cold-observation");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(any(), eq(false));
        switch (root) {
            case OBSERVE, SCHEDULED -> verify(chain).getUnspentOutputsFromScan(RECEIVE, 50);
            case ZMQ -> verify(history).save(argThat(row -> row.getDirection() == KfeDirection.INBOUND));
            case REFRESH, TOUCH -> {
                assertThat(tx.getConfirmations()).isEqualTo(4);
                assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
            }
            case PSBT -> verify(history).save(argThat(row -> row.getDirection() == KfeDirection.OUTBOUND));
        }
        assertUnavailable(() -> service.touchColdConfirmations(tx.getId(), 5));
        verify(store, times(2)).admit(anyString());
        assertThat(guard.status().blockers()).containsEntry("admissionsUncertain", 1L);
    }

    @Test
    void admittedDescriptorScanCanFinishAfterDrainAndMaterializeGapAddressAndHistory() {
        prepare();
        when(history.findByWalletIdAndStatusIn(eq(wallet.getId()), any())).thenReturn(List.of());
        when(chain.getUnspentOutputsFromScan(RECEIVE, 50)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            draining.set(true);
            return List.of(new BlockchainClient.AddressUtxo("fixture-inbound", 0, 50_000L,
                    "0014", 4, "bcrt1qgapfixture"));
        });
        when(syncs.getIfAvailable()).thenReturn(sync);
        when(sync.applyObserved(eq(wallet.getId()), any(ChainProbeResult.class))).thenReturn(50_000L);
        service.observeWallet(wallet.getId());
        verify(chain).getUnspentOutputsFromScan(CHANGE, 50);
        verify(addresses).save(argThat(row -> wallet.getId().equals(row.getWalletId())
                && "bcrt1qgapfixture".equals(row.getAddress())));
        verify(history).save(argThat(row -> row.getReceiverAmountSats() == 50_000L
                && row.getStatus() == KfeTransactionStatus.SETTLED));
        verify(statements).recordUserStatement(eq(7L), eq(wallet.getId()), any(), any());
        verify(sync).applyObserved(eq(wallet.getId()), argThat((ChainProbeResult probe) ->
                probe.sats() == 50_000L && probe.authoritative()));
        verify(store).admit("cold-observation.observe-wallet");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(any(), eq(false));
        assertUnavailable(() -> service.observeWallet(wallet.getId()));
    }

    @Test
    void schedulerKeepsBatchBoundAndCaughtWalletFailureLeavesOneUncertainRoot() {
        prepare();
        KfeWalletEntity failed = new KfeWalletEntity();
        failed.setId(UUID.randomUUID());
        KfeWalletEntity outsideBatch = new KfeWalletEntity();
        outsideBatch.setId(UUID.randomUUID());
        when(wallets.findByKindInAndStatus(List.of(KfeWalletKind.WATCH_ONLY), KfeWalletStatus.ACTIVE))
                .thenReturn(List.of(failed, wallet, outsideBatch));
        when(wallets.findById(failed.getId())).thenThrow(new IllegalStateException("wallet lookup failed"));
        service.reconcileColdWallets();
        verify(wallets).findById(failed.getId());
        verify(chain).getUnspentOutputsFromScan(RECEIVE, 50);
        verify(wallets, never()).findById(outsideBatch.getId());
        verify(store).admit("cold-observation.reconcile");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(any(), eq(false));
    }

    @Test
    void caughtDescriptorFailureKeepsExistingNoMempoolBlindFallbackAndUncertainty() {
        prepare();
        when(syncs.getIfAvailable()).thenReturn(sync);
        when(chain.getUnspentOutputsFromScan(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("scan outcome unknown"));
        service.observeWallet(wallet.getId());
        verify(sync, never()).syncWallet(any());
        verify(sync, never()).applyObserved(any(), any(ChainProbeResult.class));
        verify(sync, never()).applyObserved(any(), anyLong());
        verify(store).resolve(any(), eq(false));
    }

    @Test
    void caughtIndexRebuildFailureStillRunsExistingScanAndRemainsUncertain() {
        prepare();
        doThrow(new IllegalStateException("index rebuild outcome unknown")).when(index).rebuild();
        service.observeWallet(wallet.getId());
        verify(index).rebuild();
        verify(chain).getUnspentOutputsFromScan(RECEIVE, 50);
        verify(store).resolve(any(), eq(false));
    }

    @Test
    void caughtConfirmationProbeFailurePreservesEntityAndDoesNotCompleteRoot() {
        prepare();
        when(chain.getRawTransaction("fixture-spend", true))
                .thenThrow(new IllegalStateException("RPC outcome unknown"));
        service.refreshConfirmations(wallet.getId());
        assertThat(tx.getConfirmations()).isEqualTo(1);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        verify(history, never()).findByIdForUpdate(any());
        verify(history, never()).save(any());
        verifyNoInteractions(statements, dashboard);
        assertThat(transactions.begins).isZero();
        verify(store).resolve(any(), eq(false));
    }

    @Test
    void caughtNotificationAndStatementRefreshFailuresDoNotCompleteSettledTouch() {
        prepare();
        when(notifications.getIfAvailable()).thenReturn(notification);
        doThrow(new IllegalStateException("notification outcome unknown")).when(notification)
                .notifyOutboundConfirmed(eq(7L), eq(tx.getId()), eq(wallet.getId()), eq("ONCHAIN"),
                        anyLong(), eq(4));
        doThrow(new IllegalStateException("display refresh failed")).when(statements)
                .refreshTransactionDisplayPayload(eq(tx), any());
        assertThat(service.touchColdConfirmations(tx.getId(), 4)).isTrue();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        verify(history).save(tx);
        verify(dashboard).publishAfterCommit(7L);
        verify(store).resolve(any(), eq(false));
    }

    @Test
    void psbtHistoryKeepsIdempotencyAmountsAndNoDuplicatePublication() {
        prepare();
        when(history.findByIdempotencyKey("cold-psbt:" + workflowId))
                .thenReturn(Optional.empty(), Optional.of(tx));
        KfeTransactionEntity created = recordPsbt();
        assertThat(created.getIdempotencyKey()).isEqualTo("cold-psbt:" + workflowId);
        assertThat(created.getTotalDebitSats()).isEqualTo(10_250L);
        assertThat(created.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        assertThat(created.getConfirmations()).isZero();
        assertThat(recordPsbt()).isSameAs(tx);
        verify(history, times(1)).save(any());
        verify(statements, times(1)).recordUserStatement(eq(7L), eq(wallet.getId()), any(), any());
        verify(dashboard, times(1)).publishAfterCommit(7L);
        verify(store, times(2)).resolve(any(), eq(false));
    }

    @Test
    void finalityAndExistingLowerConfirmationPolicyArePreserved() {
        prepare();
        assertThat(service.touchColdConfirmations(tx.getId(), 2)).isTrue();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        assertThat(service.touchColdConfirmations(tx.getId(), 3)).isTrue();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        // Existing cold touch policy is monotonic; lower/reorg observations do not rewrite it.
        assertThat(service.touchColdConfirmations(tx.getId(), 0)).isFalse();
        assertThat(tx.getConfirmations()).isEqualTo(3);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        verify(statements, times(1)).recordUserStatement(eq(7L), eq(wallet.getId()), eq(tx), any());
        verify(store, times(3)).resolve(any(), eq(false));
    }

    @Test
    void failedTransactionDoesNotSettleAndFullConfirmationRingSkipsProbe() {
        prepare();
        tx.setStatus(KfeTransactionStatus.FAILED);
        assertThat(service.touchColdConfirmations(tx.getId(), 4)).isTrue();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.FAILED);
        tx.setStatus(KfeTransactionStatus.SETTLED);
        tx.setConfirmations(6);
        service.refreshConfirmations(wallet.getId());
        verifyNoInteractions(chain);
        verify(store, times(2)).resolve(any(), eq(false));
    }

    @Test
    void parentTransactionCommitDoesNotProveDashboardDelivery() {
        prepare();
        AtomicBoolean published = new AtomicBoolean();
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { published.set(true); }
            });
            return null;
        }).when(dashboard).publishAfterCommit(7L);
        template.executeWithoutResult(status -> {
            recordPsbt();
            assertThat(published).isFalse();
            verify(store, never()).resolve(any(), anyBoolean());
        });
        assertThat(published).isTrue();
        assertThat(transactions.commits).isEqualTo(1);
        verify(store).resolve(any(), eq(false));
    }

    @Test
    void parentRollbackCannotManufactureCompletion() {
        prepare();
        template.executeWithoutResult(status -> {
            service.touchColdConfirmations(tx.getId(), 4);
            verify(store, never()).resolve(any(), anyBoolean());
            status.setRollbackOnly();
        });
        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isEqualTo(1);
        verify(store).resolve(any(), eq(false));
        // Repository mocks do not emulate persistence rollback; Spring lifecycle is real.
    }

    @Test
    void afterCommitFailureLeavesCommittedHistoryUncertain() {
        prepare();
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    throw new IllegalStateException("dashboard delivery outcome unknown");
                }
            });
            return null;
        }).when(dashboard).publishAfterCommit(7L);
        assertThatThrownBy(() -> template.executeWithoutResult(status -> recordPsbt()))
                .hasMessage("dashboard delivery outcome unknown");
        assertThat(transactions.commits).isEqualTo(1);
        verify(store).resolve(any(), eq(false));
    }

    @Test
    void resolutionStorageFailureDoesNotChangeHistoryResultOrInventRecovery() {
        prepare();
        doThrow(new IllegalStateException("resolution unavailable"))
                .when(store).resolve(any(), anyBoolean());
        assertThat(recordPsbt().getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        verify(store).resolve(any(), eq(false));
        verify(store, never()).transition(any(), any(), anyLong());
        verify(store, never()).releaseContinuation(any(), anyBoolean());
    }

    @Test
    void invalidEffectFreeInputsKeepExistingBehaviorDuringDrain() {
        draining.set(true);
        service.observeWallet(null);
        service.refreshConfirmations(null);
        service.ingestZmqRawTx(null, Set.of(wallet.getId()));
        service.ingestZmqRawTx(parsed(), null);
        service.ingestZmqRawTx(parsed(), Set.of());
        assertThatThrownBy(() -> service.recordColdPsbtBroadcast(7L, wallet.getId(), workflowId,
                " ", 1L, 0L, ADDRESS)).isInstanceOf(IllegalArgumentException.class);
        assertNoEffects();
        verifyNoInteractions(store);
    }

    @Test
    void boundedCoverageLeavesStaticBlockersAndNoRecoveryAuthority() {
        draining.set(true);
        var status = guard.status();
        assertThat(status.safeToUpdate()).isFalse();
        assertThat(status.blockers()).containsEntry("mutationCoverageUnknown", 1L)
                .containsEntry("callbackCoverageUnknown", 1L).containsEntry("readSideEffectsUnknown", 1L);
        verify(store, never()).transition(any(), any(), anyLong());
        verify(store, never()).resolve(any(), anyBoolean());
    }

    private void prepare() {
        when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        when(wallets.findByKindInAndStatus(List.of(KfeWalletKind.WATCH_ONLY), KfeWalletStatus.ACTIVE))
                .thenReturn(List.of(wallet));
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setWalletId(wallet.getId());
        address.setAddress(ADDRESS);
        when(addresses.findFirstByAddressIgnoreCase(ADDRESS)).thenReturn(Optional.of(address));
        when(addresses.findByWalletIdOrderByCreatedAtDesc(wallet.getId())).thenReturn(List.of(address));
        when(chains.getIfAvailable()).thenReturn(chain);
        when(indexes.getIfAvailable()).thenReturn(index);
        when(descriptors.resolveReceiveDescriptor(wallet)).thenReturn(RECEIVE);
        when(chain.getUnspentOutputsFromScan(anyString(), anyInt())).thenReturn(List.of());
        when(chain.getUnspentOutputsMerged(ADDRESS)).thenReturn(List.of());
        when(chain.isOutpointUnspentIncludingMempool(anyString(), anyInt())).thenReturn(true);
        when(chain.getRawTransaction("fixture-spend", true))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("confirmations", 4));
        when(history.findByWalletIdAndStatusIn(eq(wallet.getId()), any())).thenReturn(List.of(tx));
        when(history.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));
        when(history.findByDestinationWalletIdAndProvider(wallet.getId(),
                KfeColdWalletObservationService.PROVIDER_COLD_OBSERVER)).thenReturn(List.of());
        when(history.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(balances.findByWalletIds(any())).thenReturn(List.of());
    }

    private KfeBitcoinZmqTxMatcher.ParsedRawTx parsed() {
        return new KfeBitcoinZmqTxMatcher.ParsedRawTx("fixture-inbound", List.of(),
                List.of(new KfeBitcoinZmqTxMatcher.ParsedOutput(ADDRESS, 50_000L, 0)));
    }

    private KfeTransactionEntity recordPsbt() {
        return service.recordColdPsbtBroadcast(7L, wallet.getId(), workflowId,
                "fixture-spend", 10_000L, 250L, ADDRESS);
    }

    private void call(Root root, KfeColdWalletObservationService target) {
        switch (root) {
            case OBSERVE -> target.observeWallet(wallet.getId());
            case ZMQ -> target.ingestZmqRawTx(parsed(), Set.of(wallet.getId()));
            case REFRESH -> target.refreshConfirmations(wallet.getId());
            case PSBT -> target.recordColdPsbtBroadcast(7L, wallet.getId(), workflowId,
                    "fixture-spend", 10_000L, 250L, ADDRESS);
            case TOUCH -> target.touchColdConfirmations(tx.getId(), 4);
            case SCHEDULED -> target.reconcileColdWallets();
        }
    }

    private String operation(Root root) {
        return switch (root) {
            case OBSERVE -> "cold-observation.observe-wallet";
            case ZMQ -> "cold-observation.ingest-zmq-raw-tx";
            case REFRESH -> "cold-observation.refresh-confirmations";
            case PSBT -> "cold-observation.record-psbt-broadcast";
            case TOUCH -> "cold-observation.touch-confirmations";
            case SCHEDULED -> "cold-observation.reconcile";
        };
    }

    private void assertRejectedOrPaused(Root root, KfeColdWalletObservationService target) {
        if (root == Root.SCHEDULED) {
            assertThatCode(() -> call(root, target)).doesNotThrowAnyException();
        } else {
            assertUnavailable(() -> call(root, target));
        }
    }

    private void assertUnavailable(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(KfeMaintenanceGuard.MaintenanceException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(503));
    }

    private void assertNoEffects() {
        verifyNoInteractions(wallets, addresses, history, balances, chains, chain, statements, dashboard,
                syncs, sync, descriptors, indexes, index, notifications, notification);
        assertThat(transactions.begins).isZero();
        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isZero();
    }

    private KfeColdWalletObservationService newService() {
        KfeBitcoinFinalityPolicy finality = new KfeBitcoinFinalityPolicy();
        finality.setCreditConfirmations(3);
        return new KfeColdWalletObservationService(wallets, addresses, history, chains, statements,
                dashboard, syncs, balances, descriptors, indexes, notifications, template, 2, finality, 50);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() { return mock(ObjectProvider.class); }

    private static final class RecordingTransactions extends AbstractPlatformTransactionManager {
        private int begins;
        private int commits;
        private int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { begins++; }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
