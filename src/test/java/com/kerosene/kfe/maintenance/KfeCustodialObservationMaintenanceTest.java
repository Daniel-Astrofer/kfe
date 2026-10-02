package com.kerosene.kfe.maintenance;

import com.kerosene.common.financial.FinancialNotificationPort;
import com.kerosene.kfe.application.transaction.KfeBalanceMovementRecorder;
import com.kerosene.kfe.config.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.model.KfeWalletAddressEntity;
import com.kerosene.kfe.model.KfeWalletAddressStatus;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.model.KfeWalletStatus;
import com.kerosene.kfe.rail.BlockchainClient;
import com.kerosene.kfe.repository.KfeBalanceMovementRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.repository.KfeWalletAddressRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.service.KfeBalanceService;
import com.kerosene.kfe.service.KfeBitcoinZmqTxMatcher;
import com.kerosene.kfe.service.KfeCustodialDepositObservationService;
import com.kerosene.kfe.service.KfeDashboardPublisher;
import com.kerosene.kfe.service.KfeFeeSettlementService;
import com.kerosene.kfe.service.KfeFinancialMetrics;
import com.kerosene.kfe.service.KfeMonitoredChainAddressIndex;
import com.kerosene.kfe.service.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.service.KfePricingService;
import com.kerosene.kfe.service.KfeResponseMapper;
import com.kerosene.kfe.service.KfeStatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real guard and Spring transaction lifecycle; financial, RPC and storage boundaries are mocked. */
class KfeCustodialObservationMaintenanceTest {
    private enum Root { OBSERVE, RAW_TX, SCHEDULED }

    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService realGuard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0L);
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicLong uncertain = new AtomicLong();
    private final Map<UUID, String> children = new LinkedHashMap<>();
    private final Deque<Runnable> pendingNotifications = new ArrayDeque<>();
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addresses = mock(KfeWalletAddressRepository.class);
    private final KfeTransactionRepository deposits = mock(KfeTransactionRepository.class);
    private final KfeBalanceMovementRepository movements = mock(KfeBalanceMovementRepository.class);
    private final ObjectProvider<BlockchainClient> chains = provider();
    private final BlockchainClient chain = mock(BlockchainClient.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRecorder recorder = mock(KfeBalanceMovementRecorder.class);
    private final KfePricingService pricing = mock(KfePricingService.class);
    private final KfeFeeSettlementService fees = mock(KfeFeeSettlementService.class);
    private final KfeStatementService statements = mock(KfeStatementService.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboard = mock(KfeDashboardPublisher.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final ObjectProvider<FinancialNotificationPort> notifications = provider();
    private final FinancialNotificationPort notification = mock(FinancialNotificationPort.class);
    private final KfeFinancialMetrics metrics = mock(KfeFinancialMetrics.class);
    private final ObjectProvider<KfeOnchainBalanceSyncService> syncs = provider();
    private final KfeOnchainBalanceSyncService sync = mock(KfeOnchainBalanceSyncService.class);
    private final ObjectProvider<KfeMonitoredChainAddressIndex> indexes = provider();
    private final KfeMonitoredChainAddressIndex index = mock(KfeMonitoredChainAddressIndex.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final TransactionTemplate template = new TransactionTemplate(transactions);
    private final KfeWalletEntity wallet = new KfeWalletEntity();
    private final KfeTransactionEntity known = new KfeTransactionEntity();
    private KfeCustodialDepositObservationService service;

    @BeforeEach
    void explicitActiveMockStoreFixtureWithRealGuard() {
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
            UUID id = invocation.getArgument(0);
            if (children.containsKey(id)) {
                assertThat(children.get(id)).isEqualTo("IN_FLIGHT");
                children.put(id, invocation.<Boolean>getArgument(1) ? "COMPLETED" : "UNCERTAIN");
            }
            return null;
        }).when(store).resolve(any(), anyBoolean());
        when(store.captureContinuation(eq(admission.id()),
                eq("custodial-observation.deposit-notification"), anyBoolean())).thenAnswer(invocation -> {
            boolean waiting = invocation.getArgument(2);
            assertThat(waiting).isEqualTo(TransactionSynchronizationManager.isActualTransactionActive());
            UUID id = UUID.randomUUID();
            children.put(id, waiting ? "WAITING" : "READY");
            return new KfeMaintenanceStore.Admission(id, admission.revision());
        });
        doAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            assertThat(children.get(id)).isEqualTo("WAITING");
            children.put(id, invocation.<Boolean>getArgument(1) ? "READY" : "CANCELLED");
            return null;
        }).when(store).releaseContinuation(any(), anyBoolean());
        when(store.claimContinuation(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            UUID id = invocation.getArgument(0);
            if (!"READY".equals(children.get(id))) {
                throw new KfeMaintenanceGuard.MaintenanceException(409, "Child is not ready or already claimed.");
            }
            children.put(id, "IN_FLIGHT");
            return new KfeMaintenanceStore.Admission(id, admission.revision());
        });
        when(store.observe()).thenAnswer(invocation -> new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(draining.get()
                        ? KfeMaintenanceGuard.Mode.DRAINING : KfeMaintenanceGuard.Mode.ACTIVE,
                        "custodial-fixture", 0L), Instant.now(),
                Map.of("admissionsUncertain", uncertain.get(),
                        "continuationsWaiting", childCount("WAITING"),
                        "continuationsReady", childCount("READY"),
                        "admissionsInFlight", childCount("IN_FLIGHT"))));
        wallet.setId(UUID.randomUUID());
        wallet.setUserId(42L);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        known.setUserId(42L);
        known.setDestinationWalletId(wallet.getId());
        known.setRail(KfeRail.ONCHAIN);
        known.setDirection(KfeDirection.INBOUND);
        known.setProvider(KfeCustodialDepositObservationService.PROVIDER_CUSTODIAL_OBSERVER);
        known.setBlockchainTxid("known-deposit");
        known.setStatus(KfeTransactionStatus.VALIDATING);
        known.setConfirmations(1);
        known.setGrossAmountSats(1_010L);
        known.setReceiverAmountSats(1_000L);
        known.setConfirmationMonitoringActive(true);
        known.setLastChainProbeAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        known.setLastChainProbeStatus("FOUND");
        service = newService();
        service.setMaintenanceGuard(realGuard);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void drainingRejectsBeforeScansTransactionsOrManagedEntityChanges(Root root) {
        prepareProbe(3);
        prepareFinality();
        draining.set(true);
        assertRejected(root, service);
        assertNoEffects();
        verify(store).admit(operation(root));
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void admissionStoreOutageRejectsBeforeEveryEffect(Root root) {
        prepareProbe(3);
        prepareFinality();
        when(store.admit(anyString())).thenThrow(new IllegalStateException("store unavailable"));
        assertRejected(root, service);
        assertNoEffects();
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void missingInjectionFailsClosedBeforeEveryEffect(Root root) {
        prepareProbe(3);
        prepareFinality();
        assertRejected(root, newService());
        assertNoEffects();
        verifyNoInteractions(store);
    }

    @Test
    void injectionIsMandatoryAndRejectsNull() throws Exception {
        Autowired annotation = KfeCustodialDepositObservationService.class
                .getMethod("setMaintenanceGuard", KfeMaintenanceGuard.class).getAnnotation(Autowired.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.required()).isTrue();
        assertThatThrownBy(() -> service.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void currentParentCanInvokeEveryNestedRootDuringDrain(Root root) {
        prepareProbe(0);
        prepareRawTx();
        when(wallets.findByKindInAndStatus(any(), any())).thenReturn(List.of(wallet));
        when(deposits.findInboundUnderReorgMonitoring(any(), any(), any(), any())).thenReturn(List.of());
        realGuard.executeMutation("custodial-fixture.parent", () -> {
            draining.set(true);
            call(root, service);
            return true;
        });
        verify(deposits).save(any(KfeTransactionEntity.class));
        verify(store, times(1)).admit(anyString());
        verify(store).admit("custodial-fixture.parent");
        verify(store).resolve(admission.id(), false);
        assertRejected(root, service);
        verify(store, times(2)).admit(anyString());
        assertThat(realGuard.status().safeToUpdate()).isFalse();
        assertThat(realGuard.status().blockers()).containsEntry("admissionsUncertain", 1L)
                .containsEntry("mutationCoverageUnknown", 1L)
                .containsEntry("callbackCoverageUnknown", 1L)
                .containsEntry("readSideEffectsUnknown", 1L);
    }

    @Test
    void schedulerKeepsBatchBoundAndFinishesWalletAndFinalityEffectsAfterDrainStarts() {
        prepareProbe(0);
        prepareDepositWrites();
        prepareFinality();
        KfeWalletEntity missing = new KfeWalletEntity();
        missing.setId(UUID.randomUUID());
        KfeWalletEntity outsideBatch = new KfeWalletEntity();
        outsideBatch.setId(UUID.randomUUID());
        when(wallets.findByKindInAndStatus(any(), any())).thenReturn(List.of(wallet, missing, outsideBatch));
        when(chain.getUnspentOutputsMerged("bcrt1qfixture")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            draining.set(true);
            return utxos(0);
        });
        when(chain.findTransactionConfirmations(known.getBlockchainTxid())).thenAnswer(invocation -> {
            assertThat(draining.get()).isTrue();
            return OptionalInt.of(3);
        });

        service.reconcileCustodialDeposits();

        verify(wallets).findById(missing.getId());
        verify(wallets, never()).findById(outsideBatch.getId());
        assertThat(known.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        assertThat(known.getConfirmations()).isEqualTo(3);
        verify(balances).creditAvailable(wallet.getId(), "BTC", 1_000L);
        verify(fees).creditKeroseneFee(known);
        verify(deposits).findInboundUnderReorgMonitoring(eq(KfeRail.ONCHAIN), eq(KfeDirection.INBOUND),
                eq(List.of(KfeTransactionStatus.VALIDATING, KfeTransactionStatus.SETTLED,
                        KfeTransactionStatus.REORG_RECONCILIATION)),
                argThat(page -> page.getPageNumber() == 0 && page.getPageSize() == 2));
        assertThat(transactions.commits).isEqualTo(2);
        verify(store, times(1)).admit(anyString());
        verify(store).admit("custodial-observation.reconcile");
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void observedConfirmedDepositCompletesLocallyAfterDrainButRemoteCompletionStaysUncertain() {
        prepareProbe(3);
        prepareDepositWrites();
        when(chain.getUnspentOutputsMerged("bcrt1qfixture")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            draining.set(true);
            return utxos(3);
        });
        when(syncs.getIfAvailable()).thenReturn(sync);
        when(sync.syncWallet(wallet.getId())).thenReturn(25_000L);

        service.observeWallet(wallet.getId());

        verify(balances).creditAvailable(wallet.getId(), "BTC", 25_000L);
        verify(metrics).setBalanceDivergenceSats(25_000L);
        runNotifications();
        verify(notification).notifyDepositConfirmed(eq(42L), any(), eq(wallet.getId()),
                eq("ONCHAIN"), eq(25_000L), eq(3));
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        assertUnavailable(() -> service.observeWallet(wallet.getId()));
    }

    @Test
    void caughtMergedUtxoScanFailureCannotCompleteAdmission() {
        prepareProbe(0);
        when(chain.getUnspentOutputsMerged("bcrt1qfixture"))
                .thenThrow(new IllegalStateException("descriptor scan outcome unknown"));
        service.observeWallet(wallet.getId());
        verifyNoInteractions(deposits, movements, recorder, balances, fees, statements, dashboard, audit);
        assertThat(transactions.commits).isZero();
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void caughtQuoteFailureCannotCompleteAdmissionOrWriteDeposit() {
        prepareRawTx();
        when(pricing.quote(any(), any(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("provider outcome unknown"));
        service.ingestZmqRawTx(rawTx(), Set.of(wallet.getId()));
        verify(deposits, never()).save(any());
        verifyNoInteractions(balances, fees, statements, dashboard, notification);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void finalityProviderFailurePropagatesWithoutManagedEntityMutationAndStaysUncertain() {
        prepareFinality();
        when(chain.findTransactionConfirmations(known.getBlockchainTxid()))
                .thenThrow(new IllegalStateException("confirmation outcome unknown"));
        assertThatThrownBy(service::reconcileCustodialDeposits)
                .isInstanceOf(IllegalStateException.class).hasMessage("confirmation outcome unknown");
        verify(deposits, never()).findByIdForUpdate(any());
        assertThat(known.getConfirmations()).isEqualTo(1);
        assertThat(known.getLastChainProbeAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(transactions.commits).isZero();
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void caughtCommitFailureRollsBackAndDoesNotFireAfterCommitNotification() {
        prepareRawTx();
        doThrow(new IllegalStateException("statement failed"))
                .when(statements).recordUserStatement(anyLong(), any(), any(), any());
        service.ingestZmqRawTx(rawTx(), Set.of(wallet.getId()));
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
        verifyNoInteractions(notification, dashboard);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void notificationFailureAfterSuccessfulCommitRemainsUncertain() {
        prepareRawTx();
        doAnswer(invocation -> {
            assertThat(transactions.commits).isEqualTo(1);
            draining.set(true);
            throw new IllegalStateException("notification outcome unknown");
        }).when(notification).notifyDepositDetected(anyLong(), any(), any(), anyString(), anyLong(), anyInt());
        service.ingestZmqRawTx(rawTx(), Set.of(wallet.getId()));
        assertThat(transactions.commits).isEqualTo(1);
        runNotifications();
        verify(dashboard).publishAfterCommit(42L);
        verify(store).resolve(admission.id(), false);
        assertThat(realGuard.status().safeToUpdate()).isFalse();
    }

    @Test
    void successfulAfterCommitNotificationStillCannotCertifyRemoteCompletion() {
        prepareRawTx();
        doAnswer(invocation -> {
            assertThat(transactions.commits).isEqualTo(1);
            return null;
        }).when(notification).notifyDepositDetected(anyLong(), any(), any(), anyString(), anyLong(), anyInt());
        service.ingestZmqRawTx(rawTx(), Set.of(wallet.getId()));
        runNotifications();
        verify(notification).notifyDepositDetected(eq(42L), any(), eq(wallet.getId()),
                eq("ONCHAIN"), eq(25_000L), eq(0));
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @Test
    void callerTransactionRollbackDefersResolutionAndSuppressesNotification() {
        prepareRawTx();
        assertThatThrownBy(() -> template.executeWithoutResult(ignored -> {
            service.ingestZmqRawTx(rawTx(), Set.of(wallet.getId()));
            verify(store, never()).resolve(any(), anyBoolean());
            verifyNoInteractions(notification);
            throw new IllegalStateException("caller rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("caller rollback");
        assertThat(transactions.rollbacks).isEqualTo(1);
        verifyNoInteractions(notification);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void callerCommitDefersResolutionWithoutCertifyingNotificationCompletion() {
        prepareRawTx();
        template.executeWithoutResult(ignored -> {
            service.ingestZmqRawTx(rawTx(), Set.of(wallet.getId()));
            verify(store, never()).resolve(any(), anyBoolean());
            verifyNoInteractions(notification);
            draining.set(true);
        });
        assertThat(transactions.commits).isEqualTo(1);
        runNotifications();
        verify(notification).notifyDepositDetected(eq(42L), any(), eq(wallet.getId()),
                eq("ONCHAIN"), eq(25_000L), eq(0));
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void caughtObservedBalanceResyncFailureDoesNotCertifyCompletion() {
        prepareProbe(0);
        prepareDepositWrites();
        when(syncs.getIfAvailable()).thenReturn(sync);
        when(sync.syncWallet(wallet.getId())).thenThrow(new IllegalStateException("resync unavailable"));
        service.observeWallet(wallet.getId());
        verify(dashboard).publishAfterCommit(42L);
        verifyNoInteractions(metrics);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void depositChildIsCapturedBeforeCommitAndClaimsOnceWhileDraining() {
        prepareRawTx();
        template.executeWithoutResult(ignored -> {
            service.ingestZmqRawTx(rawTx(), Set.of(wallet.getId()));
            assertThat(children.values()).containsExactly("WAITING");
            assertThat(pendingNotifications).isEmpty();
            verifyNoInteractions(notification);
            draining.set(true);
        });
        assertThat(children.values()).containsExactly("READY");
        assertThat(pendingNotifications).hasSize(1);
        Runnable retained = pendingNotifications.removeFirst();
        retained.run();
        verify(store, times(1)).admit(anyString());
        verify(store).captureContinuation(admission.id(), "custodial-observation.deposit-notification", true);
        verify(notification).notifyDepositDetected(eq(42L), any(), eq(wallet.getId()),
                eq("ONCHAIN"), eq(25_000L), eq(0));
        assertThat(children.values()).containsExactly("UNCERTAIN");
        assertThatThrownBy(retained::run).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verify(notification, times(1)).notifyDepositDetected(anyLong(), any(), any(), anyString(), anyLong(), anyInt());
        verify(store, never()).resolve(any(), eq(true));
    }

    @Test
    void depositRollbackCancelsUnstartedChildWithoutDispatch() {
        prepareRawTx();
        template.executeWithoutResult(status -> {
            service.ingestZmqRawTx(rawTx(), Set.of(wallet.getId()));
            assertThat(children.values()).containsExactly("WAITING");
            status.setRollbackOnly();
        });
        assertThat(children.values()).containsExactly("CANCELLED");
        assertThat(pendingNotifications).isEmpty();
        verifyNoInteractions(notification);
        verify(store, never()).claimContinuation(any());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void missingNotifierAndInertRawInputNeverCreatePhantomContinuation() {
        service.ingestZmqRawTx(null, Set.of(wallet.getId()));
        service.ingestZmqRawTx(rawTx(), Set.of());
        service.ingestZmqRawTx(rawTx(), null);
        verifyNoInteractions(store); assertNoEffects();
        prepareRawTx();
        when(notifications.getIfAvailable()).thenReturn(null);
        service.ingestZmqRawTx(rawTx(), Set.of(wallet.getId()));
        verify(store, never()).captureContinuation(any(), anyString(), anyBoolean());
        assertThat(children).isEmpty(); assertThat(pendingNotifications).isEmpty();
    }

    @Test
    void notificationExecutorRejectionRetainsReadyChildWithoutDispatch() {
        prepareRawTx();
        ReflectionTestUtils.invokeMethod(service, "setDepositNotificationExecutor",
                (Executor) work -> { throw new java.util.concurrent.RejectedExecutionException("synthetic reject"); });
        service.ingestZmqRawTx(rawTx(), Set.of(wallet.getId()));
        assertThat(children.values()).containsExactly("READY");
        assertThat(pendingNotifications).isEmpty(); verifyNoInteractions(notification);
        verify(store, never()).claimContinuation(any());
        assertThat(realGuard.status().safeToUpdate()).isFalse();
    }

    @Test
    void notificationChildClaimOutageCannotCallPortOrManufactureCompletion() {
        prepareRawTx();
        service.ingestZmqRawTx(rawTx(), Set.of(wallet.getId()));
        doThrow(new IllegalStateException("synthetic claim outage")).when(store).claimContinuation(any());
        assertThatThrownBy(this::runNotifications).hasMessage("synthetic claim outage");
        assertThat(children.values()).containsExactly("READY");
        verifyNoInteractions(notification);
        verify(store, never()).resolve(any(), eq(true));
    }

    private void prepareProbe(int confirmations) {
        when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        when(chains.getIfAvailable()).thenReturn(chain);
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setAddress("bcrt1qfixture");
        address.setStatus(KfeWalletAddressStatus.ACTIVE);
        when(addresses.findByWalletIdOrderByCreatedAtDesc(wallet.getId())).thenReturn(List.of(address));
        when(chain.getUnspentOutputsMerged("bcrt1qfixture")).thenReturn(utxos(confirmations));
    }

    private List<BlockchainClient.AddressUtxo> utxos(int confirmations) {
        return List.of(new BlockchainClient.AddressUtxo("aabbccdd", 0, 25_000L, "",
                confirmations, "bcrt1qfixture"));
    }

    private void prepareRawTx() {
        when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        when(indexes.getIfAvailable()).thenReturn(index);
        when(index.walletIdForAddress("bcrt1qfixture")).thenReturn(wallet.getId());
        prepareDepositWrites();
    }

    private void prepareDepositWrites() {
        when(deposits.findByBlockchainTxidAndUserId("aabbccdd", 42L)).thenReturn(List.of());
        when(deposits.findByIdempotencyKey("custodial-dep:" + wallet.getId() + ":aabbccdd"))
                .thenReturn(Optional.empty());
        when(pricing.quote(any(), any(), eq(25_000L), eq(0L)))
                .thenReturn(new KfePricingService.Quote(25_000L, 25_000L, 0L, 0L));
        when(deposits.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.buildDisplayPayload(any(), eq(42L))).thenReturn(Map.of());
        when(recorder.record(any(), eq(wallet.getId()), anyString(), anyLong(), any(), any()))
                .thenReturn(true);
        when(notifications.getIfAvailable()).thenReturn(notification);
    }

    private void prepareFinality() {
        when(chains.getIfAvailable()).thenReturn(chain);
        when(wallets.findByKindInAndStatus(any(), any())).thenReturn(List.of());
        when(deposits.findInboundUnderReorgMonitoring(any(), any(), any(), any())).thenReturn(List.of(known));
        when(deposits.findByIdForUpdate(known.getId())).thenReturn(Optional.of(known));
        when(chain.findTransactionConfirmations(known.getBlockchainTxid())).thenReturn(OptionalInt.of(3));
        when(recorder.record(eq(known.getId()), eq(wallet.getId()), anyString(), eq(1_000L), any(), any()))
                .thenReturn(true);
        when(mapper.buildDisplayPayload(known, 42L)).thenReturn(Map.of());
    }

    private KfeBitcoinZmqTxMatcher.ParsedRawTx rawTx() {
        return new KfeBitcoinZmqTxMatcher.ParsedRawTx("aabbccdd", List.of(),
                List.of(new KfeBitcoinZmqTxMatcher.ParsedOutput("bcrt1qfixture", 25_000L, 0)));
    }

    private void call(Root root, KfeCustodialDepositObservationService target) {
        switch (root) {
            case OBSERVE -> target.observeWallet(wallet.getId());
            case RAW_TX -> target.ingestZmqRawTx(rawTx(), Set.of(wallet.getId()));
            case SCHEDULED -> target.reconcileCustodialDeposits();
        }
    }

    private String operation(Root root) {
        return switch (root) {
            case OBSERVE -> "custodial-observation.observe-wallet";
            case RAW_TX -> "custodial-observation.ingest-zmq-raw-tx";
            case SCHEDULED -> "custodial-observation.reconcile";
        };
    }

    private void assertRejected(Root root, KfeCustodialDepositObservationService target) {
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
        verifyNoInteractions(wallets, addresses, deposits, movements, chains, chain, balances, recorder,
                pricing, fees, statements, mapper, dashboard, audit, notifications, notification,
                metrics, syncs, sync, indexes, index);
        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isZero();
        assertThat(known.getStatus()).isEqualTo(KfeTransactionStatus.VALIDATING);
        assertThat(known.getConfirmations()).isEqualTo(1);
        assertThat(known.getLastChainProbeAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(known.getLastChainProbeStatus()).isEqualTo("FOUND");
        assertThat(known.isConfirmationMonitoringActive()).isTrue();
    }

    private KfeCustodialDepositObservationService newService() {
        KfeBitcoinFinalityPolicy policy = new KfeBitcoinFinalityPolicy();
        policy.setCreditConfirmations(3);
        policy.setFinalityConfirmations(6);
        policy.setReorgMonitorConfirmations(12);
        KfeCustodialDepositObservationService created = new KfeCustodialDepositObservationService(wallets, addresses, deposits, movements,
                chains, balances, recorder, pricing, fees, statements, mapper, dashboard, audit,
                notifications, metrics, syncs, indexes, template, 2, policy, 3, 0L);
        ReflectionTestUtils.invokeMethod(created, "setDepositNotificationExecutor",
                (Executor) pendingNotifications::addLast);
        return created;
    }

    private long childCount(String state) {
        return children.values().stream().filter(state::equals).count();
    }

    private void runNotifications() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        while (!pendingNotifications.isEmpty()) {
            pendingNotifications.removeFirst().run();
        }
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
