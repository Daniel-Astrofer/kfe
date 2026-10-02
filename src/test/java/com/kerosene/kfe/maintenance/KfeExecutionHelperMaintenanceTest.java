package com.kerosene.kfe.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.FinancialNotificationPort;
import com.kerosene.kfe.application.transaction.KfeBalanceMovementRecorder;
import com.kerosene.kfe.application.transaction.KfePlatformOnchainDestinationRouter;
import com.kerosene.kfe.audit.KfeAuditEventLogger;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeExecutionOutboxEntity;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.rail.BitcoinCoreRpcClient;
import com.kerosene.kfe.repository.KfeBalanceMovementRepository;
import com.kerosene.kfe.repository.KfeExecutionOutboxRepository;
import com.kerosene.kfe.repository.KfeIdempotencyRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.service.KfeBalanceService;
import com.kerosene.kfe.service.KfeCustodialDepositObservationService;
import com.kerosene.kfe.service.KfeDashboardPublisher;
import com.kerosene.kfe.service.KfeExecutionTransactionHelper;
import com.kerosene.kfe.service.KfeFeeSettlementService;
import com.kerosene.kfe.service.KfeFinancialMetrics;
import com.kerosene.kfe.service.KfeHashService;
import com.kerosene.kfe.service.KfeLightningLiquidityService;
import com.kerosene.kfe.service.KfeNetworkFeeEstimateService;
import com.kerosene.kfe.service.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.service.KfePlatformPeerInboundService;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real guard and Spring completion; explicit mock-store child states, never fake settlement success. */
class KfeExecutionHelperMaintenanceTest {
    private enum Root {
        PREPARE, BROADCAST, TOUCH, LEGACY_TOUCH, CONFIRMED, SETTLE, LIGHTNING,
        UNKNOWN, RETRY, FAILURE, LEGACY_FAILURE, RECONCILE, LEGACY_RECONCILE, CONFLICT
    }

    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService realGuard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission parent =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0L);
    private final AtomicBoolean draining = new AtomicBoolean();
    private final Map<UUID, String> states = new LinkedHashMap<>();
    private final List<UUID> children = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final QueuedExecutor queue = new QueuedExecutor();
    private final KfeExecutionOutboxRepository outboxes = mock(KfeExecutionOutboxRepository.class);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeIdempotencyRepository idempotency = mock(KfeIdempotencyRepository.class);
    private final KfeBalanceMovementRepository movements = mock(KfeBalanceMovementRepository.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final KfeStatementService statements = mock(KfeStatementService.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboard = mock(KfeDashboardPublisher.class);
    private final KfeHashService hashes = mock(KfeHashService.class);
    private final KfeFeeSettlementService fees = mock(KfeFeeSettlementService.class);
    private final KfeNetworkFeeEstimateService estimates = mock(KfeNetworkFeeEstimateService.class);
    private final ObjectProvider<KfeOnchainBalanceSyncService> syncs = provider();
    private final KfeOnchainBalanceSyncService sync = mock(KfeOnchainBalanceSyncService.class);
    private final ObjectProvider<KfeLightningLiquidityService> liquidity = provider();
    private final ObjectProvider<KfeCustodialDepositObservationService> observers = provider();
    private final ObjectProvider<KfePlatformOnchainDestinationRouter> routers = provider();
    private final ObjectProvider<KfePlatformPeerInboundService> peers = provider();
    private final KfePlatformPeerInboundService peer = mock(KfePlatformPeerInboundService.class);
    private final ObjectProvider<FinancialNotificationPort> notifications = provider();
    private final FinancialNotificationPort notification = mock(FinancialNotificationPort.class);
    private final ObjectProvider<BitcoinCoreRpcClient> cores = provider();
    private final BitcoinCoreRpcClient core = mock(BitcoinCoreRpcClient.class);
    private final KfeBalanceMovementRecorder recorder = mock(KfeBalanceMovementRecorder.class);
    private final KfeFinancialMetrics metrics = mock(KfeFinancialMetrics.class);
    private final KfeAuditEventLogger auditEvents = mock(KfeAuditEventLogger.class);
    private final KfeWalletEntity wallet = new KfeWalletEntity();
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
    private final UUID claimToken = UUID.randomUUID();
    private final RecordingTransactions manager = new RecordingTransactions();
    private final TransactionTemplate template = new TransactionTemplate(manager);
    private KfeExecutionTransactionHelper helper;

    @BeforeEach
    void explicitActiveFixtureWithDurableChildLifecycle() {
        when(store.admit(anyString())).thenAnswer(invocation -> {
            if (draining.get()) {
                throw new KfeMaintenanceGuard.MaintenanceException(503, "KFE is draining.");
            }
            states.put(parent.id(), "IN_FLIGHT");
            return parent;
        });
        when(store.captureContinuation(any(), anyString(), anyBoolean())).thenAnswer(invocation -> {
            assertThat(states.get(invocation.<UUID>getArgument(0))).isEqualTo("IN_FLIGHT");
            UUID child = UUID.randomUUID();
            children.add(child);
            states.put(child, invocation.<Boolean>getArgument(2) ? "WAITING" : "READY");
            events.add("capture");
            return new KfeMaintenanceStore.Admission(child, 0L);
        });
        doAnswer(invocation -> {
            UUID child = invocation.getArgument(0);
            assertThat(states.get(child)).isEqualTo("WAITING");
            states.put(child, invocation.<Boolean>getArgument(1) ? "READY" : "CANCELLED");
            events.add("release");
            return null;
        }).when(store).releaseContinuation(any(), anyBoolean());
        when(store.claimContinuation(any())).thenAnswer(invocation -> {
            UUID child = invocation.getArgument(0);
            assertThat(states.get(child)).isEqualTo("READY");
            states.put(child, "IN_FLIGHT");
            events.add("claim");
            return new KfeMaintenanceStore.Admission(child, 0L);
        });
        doAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            assertThat(states.get(id)).isEqualTo("IN_FLIGHT");
            states.put(id, invocation.<Boolean>getArgument(1) ? "COMPLETED" : "UNCERTAIN");
            return null;
        }).when(store).resolve(any(), anyBoolean());
        when(store.observe()).thenAnswer(invocation -> new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(draining.get()
                        ? KfeMaintenanceGuard.Mode.DRAINING : KfeMaintenanceGuard.Mode.ACTIVE,
                        "execution-helper-fixture", 0L), Instant.now(), Map.of(
                        "admissionsUncertain", states.values().stream().filter("UNCERTAIN"::equals).count(),
                        "continuationsWaiting", states.values().stream().filter("WAITING"::equals).count(),
                        "continuationsReady", states.values().stream().filter("READY"::equals).count())));
        wallet.setId(UUID.randomUUID());
        wallet.setUserId(42L);
        wallet.setKind(KfeWalletKind.INTERNAL);
        wallet.setLabel("fixture-wallet");
        tx.setUserId(42L);
        tx.setIdempotencyKey("fixture-idempotency");
        tx.setSourceWalletId(wallet.getId());
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setGrossAmountSats(100_000L);
        tx.setReceiverAmountSats(100_000L);
        tx.setNetworkFeeSats(1_000L);
        tx.setKeroseneFeeSats(900L);
        tx.setTotalDebitSats(101_900L);
        tx.setBlockchainTxid("previous-txid");
        tx.setExternalReference("bcrt1qfixture");
        outbox.setTransactionId(tx.getId());
        outbox.setStatus("PROCESSING");
        outbox.setOperation("ONCHAIN_OUTBOUND");
        outbox.setClaimToken(claimToken);
        when(outboxes.findByIdForUpdate(outbox.getId())).thenReturn(Optional.of(outbox));
        when(outboxes.findByTransactionId(tx.getId())).thenReturn(List.of(outbox));
        when(transactions.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));
        when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        when(mapper.buildDisplayPayload(any(), any())).thenReturn(Map.of());
        when(hashes.sha256(any())).thenReturn("fixture-hash");
        helper = newHelper(queue);
        helper.setMaintenanceGuard(realGuard);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void drainingRejectsAllRootsAndOverloadsBeforeLocksRpcOrEntityEffects(Root root) {
        draining.set(true);
        assertUnavailable(() -> call(root, helper));
        assertNoEffects();
        verify(store).admit(operation(root));
        verify(store, never()).captureContinuation(any(), anyString(), anyBoolean());
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void missingInjectionRejectsAllRootsAndOverloadsBeforeEffects(Root root) {
        assertUnavailable(() -> call(root, newHelper(queue)));
        assertNoEffects();
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void admissionStorageOutageRejectsAllRootsAndOverloadsBeforeEffects(Root root) {
        doThrow(new IllegalStateException("admission unavailable")).when(store).admit(anyString());
        assertUnavailable(() -> call(root, helper));
        assertNoEffects();
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @Test
    void injectionIsMandatoryAndNullIsRejected() throws Exception {
        Autowired annotation = KfeExecutionTransactionHelper.class
                .getMethod("setMaintenanceGuard", KfeMaintenanceGuard.class).getAnnotation(Autowired.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.required()).isTrue();
        assertThatThrownBy(() -> helper.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void everyNestedRootMayFinishDuringDrainButCannotCertifyParentCompletion(Root root) {
        realGuard.executeMutation("execution-helper-fixture.parent", () -> {
            draining.set(true);
            call(root, helper);
            return true;
        });
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(parent.id(), false);
        assertThat(states.get(parent.id())).isEqualTo("UNCERTAIN");
        assertUnavailable(() -> call(root, helper));
    }

    @Test
    void confirmedSettlementCapturesBeforeCommitAndOneChildOwnsWholeActionDuringDrain() {
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);
        when(syncs.getIfAvailable()).thenReturn(sync);
        when(notifications.getIfAvailable()).thenReturn(notification);
        when(sync.syncWallet(wallet.getId())).thenAnswer(invocation -> {
            assertTransactionFree();
            assertThat(states.get(onlyChild())).isEqualTo("IN_FLIGHT");
            events.add("sync");
            return realGuard.executeMutation("execution-helper-fixture.sync", () -> 20_000L,
                    ignored -> false);
        });
        doAnswer(invocation -> {
            assertTransactionFree();
            assertThat(states.get(onlyChild())).isEqualTo("IN_FLIGHT");
            events.add("notification");
            throw new IllegalStateException("remote outcome unknown");
        }).when(notification).notifyPaymentConfirmed(anyLong(), any(), any(), anyString(), anyLong(), anyInt());

        template.executeWithoutResult(ignored -> {
            assertThat(helper.settleOutboundWhenConfirmed(tx.getId(), 4)).isTrue();
            verify(balances).settleReservedDebit(wallet.getId(), "BTC", 101_900L);
            assertThat(states.get(onlyChild())).isEqualTo("WAITING");
            assertThat(queue.pending).isEmpty();
            verifyNoInteractions(sync, notification);
            verify(store, never()).resolve(any(), anyBoolean());
            draining.set(true);
        });
        assertThat(states.get(onlyChild())).isEqualTo("READY");
        assertThat(queue.pending).hasSize(1);
        queue.runNext();

        assertThat(events).containsExactly("capture", "commit", "release", "enqueue", "claim", "sync", "notification");
        assertThat(queue.pending).isEmpty();
        verify(store, times(1)).admit(anyString());
        verify(store).captureContinuation(parent.id(), "execution-helper.after-completion", true);
        verify(store).resolve(parent.id(), false);
        verify(store).resolve(onlyChild(), false);
        verify(store, never()).resolve(any(), eq(true));
        verify(notification).notifyPaymentConfirmed(42L, tx.getId(), wallet.getId(), "ONCHAIN", 100_000L, 4);
    }

    @Test
    void rollbackCancelsOnlyUnstartedChildAndDoesNotCompleteParent() {
        when(notifications.getIfAvailable()).thenReturn(notification);
        assertThatThrownBy(() -> template.executeWithoutResult(ignored -> {
            call(Root.UNKNOWN, helper);
            assertThat(states.get(onlyChild())).isEqualTo("WAITING");
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("rollback");
        assertThat(states.get(onlyChild())).isEqualTo("CANCELLED");
        assertThat(states.get(parent.id())).isEqualTo("UNCERTAIN");
        assertThat(events).containsExactly("capture", "rollback", "release");
        assertThat(queue.pending).isEmpty();
        verify(store).releaseContinuation(onlyChild(), false);
        verify(store, never()).claimContinuation(any());
        verifyNoInteractions(notification);
    }

    @Test
    void successfulNotificationStillLeavesChildUncertainWithoutNewRootAdmission() {
        when(notifications.getIfAvailable()).thenReturn(notification);
        template.executeWithoutResult(ignored -> call(Root.UNKNOWN, helper));
        draining.set(true);
        queue.runNext();
        verify(notification).notifyPaymentReconciliationRequired(42L, tx.getId(), wallet.getId(),
                "ONCHAIN", 100_000L, "fixture-message");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(onlyChild(), false);
        assertThat(states.values()).containsOnly("UNCERTAIN");
        assertThat(realGuard.status().safeToUpdate()).isFalse();
        assertThat(realGuard.status().blockers()).containsEntry("admissionsUncertain", 2L)
                .containsEntry("mutationCoverageUnknown", 1L)
                .containsEntry("callbackCoverageUnknown", 1L)
                .containsEntry("readSideEffectsUnknown", 1L);
    }

    @Test
    void caughtPeerFailureKeepsBroadcastChildUncertainAndStillRunsNotification() {
        when(transactions.findById(tx.getId())).thenReturn(Optional.of(tx));
        when(peers.getIfAvailable()).thenReturn(peer);
        doThrow(new IllegalStateException("peer outcome unknown")).when(peer).exposeAfterOutboundBroadcast(tx);
        when(notifications.getIfAvailable()).thenReturn(notification);
        template.executeWithoutResult(ignored -> call(Root.BROADCAST, helper));
        queue.runNext();
        verify(peer).exposeAfterOutboundBroadcast(tx);
        verify(notification).notifyPaymentBroadcast(42L, tx.getId(), wallet.getId(),
                "ONCHAIN", 100_000L, "broadcast-txid");
        verify(store).resolve(onlyChild(), false);
    }

    @Test
    void outerHookFailureIsLoggedAndChildRemainsUncertain() {
        when(transactions.findById(tx.getId())).thenThrow(new IllegalStateException("post-commit lookup failed"));
        template.executeWithoutResult(ignored -> call(Root.BROADCAST, helper));
        assertThatCode(queue::runNext).doesNotThrowAnyException();
        verifyNoInteractions(notification);
        verify(store).resolve(onlyChild(), false);
    }

    @Test
    void caughtConflictRpcFailureDoesNotCertifyFinancialOrChildCompletion() {
        when(cores.getIfAvailable()).thenReturn(core);
        when(core.getRawTransaction("previous-txid", true)).thenThrow(new IllegalStateException("RPC unknown"));
        template.executeWithoutResult(ignored -> call(Root.CONFLICT, helper));
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        verify(balances, never()).releaseReserved(any(), anyString(), anyLong());
        queue.runNext();
        verify(store).resolve(parent.id(), false);
        verify(store).resolve(onlyChild(), false);
    }

    @Test
    void noTransactionCapturesReadyChildBeforeEnqueueAndUsesProvenanceDuringDrain() {
        when(notifications.getIfAvailable()).thenReturn(notification);
        call(Root.UNKNOWN, helper);
        assertThat(states.get(onlyChild())).isEqualTo("READY");
        assertThat(events).containsExactly("capture", "enqueue");
        draining.set(true);
        queue.runNext();
        verify(store).captureContinuation(parent.id(), "execution-helper.after-completion", false);
        verify(store, never()).releaseContinuation(any(), anyBoolean());
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(onlyChild(), false);
    }

    @Test
    void executorRejectionLeavesReadyChildUnresolved() {
        helper = newHelper(action -> { throw new RejectedExecutionException("rejected"); });
        helper.setMaintenanceGuard(realGuard);
        template.executeWithoutResult(ignored -> call(Root.UNKNOWN, helper));
        assertThat(states.get(onlyChild())).isEqualTo("READY");
        verify(store, never()).claimContinuation(any());
        verify(store, never()).resolve(eq(onlyChild()), anyBoolean());
        verifyNoInteractions(notification);
    }

    @Test
    void directExecutorInCompletedTransactionCannotClaimOrRunChild() {
        helper = newHelper(Runnable::run);
        helper.setMaintenanceGuard(realGuard);
        template.executeWithoutResult(ignored -> call(Root.UNKNOWN, helper));
        assertThat(states.get(onlyChild())).isEqualTo("READY");
        verify(store, never()).claimContinuation(any());
        verify(store, never()).resolve(eq(onlyChild()), anyBoolean());
        verifyNoInteractions(notification);
    }

    @Test
    void childCaptureOutageRollsBackAndNeverEnqueues() {
        doThrow(new IllegalStateException("child persistence unavailable"))
                .when(store).captureContinuation(any(), anyString(), anyBoolean());
        assertUnavailable(() -> template.executeWithoutResult(ignored -> call(Root.UNKNOWN, helper)));
        assertThat(queue.pending).isEmpty();
        assertThat(events).containsExactly("rollback");
        verify(store).resolve(parent.id(), false);
        verify(store, never()).releaseContinuation(any(), anyBoolean());
        verifyNoInteractions(notification);
    }

    @Test
    void childClaimOutageLeavesReadyChildUnresolvedWithoutRunningAction() {
        template.executeWithoutResult(ignored -> call(Root.UNKNOWN, helper));
        doThrow(new IllegalStateException("claim unavailable")).when(store).claimContinuation(any());
        assertThatThrownBy(queue::runNext).isInstanceOf(IllegalStateException.class)
                .hasMessage("claim unavailable");
        assertThat(states.get(onlyChild())).isEqualTo("READY");
        verify(store, never()).resolve(eq(onlyChild()), anyBoolean());
        verifyNoInteractions(notification);
    }

    @Test
    void pureFeeValidationDoesNotRequireAdmission() {
        assertThat(KfeExecutionTransactionHelper.validateFeeBeforeBroadcast(700L, 1_000L,
                100_000L, 0L, 0L, 0L).valid()).isTrue();
        verifyNoInteractions(store);
    }

    private void call(Root root, KfeExecutionTransactionHelper target) {
        switch (root) {
            case PREPARE -> target.prepare(outbox.getId(), claimToken);
            case BROADCAST -> target.recordOutboundBroadcast(outbox.getId(), tx.getId(), claimToken,
                    "BITCOIN_CORE", "provider-ref", "broadcast-txid", 700L, wallet.getId(), "{}");
            case TOUCH -> target.touchOutboundConfirmations(tx.getId(), 4, "block-hash", 1);
            case LEGACY_TOUCH -> target.touchOutboundConfirmations(tx.getId(), 4);
            case CONFIRMED -> target.settleOutboundWhenConfirmed(tx.getId(), 4);
            case SETTLE -> target.settleOutbound(outbox.getId(), tx.getId(), claimToken,
                    "BITCOIN_CORE", "provider-ref", "broadcast-txid", 700L, wallet.getId(), "{}");
            case LIGHTNING -> target.settleOutboundLightning(outbox.getId(), tx.getId(), claimToken,
                    "LND", "provider-ref", null, "payment-hash", 700L, wallet.getId(), "{}");
            case UNKNOWN -> target.markUnknown(outbox.getId(), tx.getId(), claimToken,
                    "provider-ref", "{}", "fixture-message");
            case RETRY -> target.markRetryableFailure(outbox.getId(), tx.getId(), claimToken,
                    "FIXTURE_CODE", "fixture-message");
            case FAILURE -> target.markFinalFailure(outbox.getId(), tx.getId(), claimToken,
                    "FIXTURE_CODE", "fixture-message");
            case LEGACY_FAILURE -> target.markFinalFailure(null, tx.getId(), "FIXTURE_CODE", "fixture-message");
            case RECONCILE -> target.markRequiresReconciliation(outbox.getId(), tx.getId(), claimToken,
                    "FIXTURE_CODE", "fixture-message");
            case LEGACY_RECONCILE -> target.markRequiresReconciliation(null, tx.getId(),
                    "FIXTURE_CODE", "fixture-message");
            case CONFLICT -> target.markOutboundConflicted(tx.getId(), -1);
        }
    }

    private String operation(Root root) {
        return "execution-helper." + switch (root) {
            case PREPARE -> "prepare";
            case BROADCAST -> "record-outbound-broadcast";
            case TOUCH, LEGACY_TOUCH -> "touch-outbound-confirmations";
            case CONFIRMED -> "settle-outbound-when-confirmed";
            case SETTLE -> "settle-outbound";
            case LIGHTNING -> "settle-outbound-lightning";
            case UNKNOWN -> "mark-unknown";
            case RETRY -> "mark-retryable-failure";
            case FAILURE, LEGACY_FAILURE -> "mark-final-failure";
            case RECONCILE, LEGACY_RECONCILE -> "mark-requires-reconciliation";
            case CONFLICT -> "mark-outbound-conflicted";
        };
    }

    private UUID onlyChild() {
        assertThat(children).hasSize(1);
        return children.getFirst();
    }

    private void assertNoEffects() {
        verifyNoInteractions(outboxes, transactions, wallets, idempotency, movements, balances,
                audit, statements, mapper, dashboard, hashes, fees, estimates, syncs, sync, liquidity,
                observers, routers, peers, peer, notifications, notification, cores, core, recorder,
                metrics, auditEvents);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        assertThat(tx.getConfirmations()).isZero();
        assertThat(tx.getNetworkFeeSats()).isEqualTo(1_000L);
        assertThat(tx.getTotalDebitSats()).isEqualTo(101_900L);
        assertThat(tx.getBlockchainTxid()).isEqualTo("previous-txid");
        assertThat(outbox.getStatus()).isEqualTo("PROCESSING");
        assertThat(outbox.getClaimToken()).isEqualTo(claimToken);
        assertThat(queue.pending).isEmpty();
        assertThat(events).isEmpty();
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(KfeMaintenanceGuard.MaintenanceException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(503));
    }

    private void assertTransactionFree() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    private KfeExecutionTransactionHelper newHelper(Executor executor) {
        KfeExecutionTransactionHelper result = new KfeExecutionTransactionHelper(outboxes, transactions,
                wallets, idempotency, movements, balances, audit, statements, mapper, dashboard, hashes,
                new ObjectMapper(), fees, estimates, syncs, liquidity, observers, routers, peers,
                notifications, cores, recorder, metrics, auditEvents, 8);
        try {
            var seam = KfeExecutionTransactionHelper.class.getDeclaredMethod("setContinuationExecutor", Executor.class);
            seam.setAccessible(true);
            seam.invoke(result, executor);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot configure the package-private executor seam.", failure);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() { return mock(ObjectProvider.class); }

    private final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        public void execute(Runnable action) {
            events.add("enqueue");
            pending.addLast(action);
        }
        private void runNext() {
            assertTransactionFree();
            pending.removeFirst().run();
        }
    }

    private final class RecordingTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { events.add("commit"); }
        @Override protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); }
    }
}
