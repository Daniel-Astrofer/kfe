package com.kerosene.kfe.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.FinancialNotificationPort;
import com.kerosene.kfe.config.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeExecutionOutboxEntity;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.rail.BitcoinCoreRpcClient;
import com.kerosene.kfe.rail.BlockchainClient;
import com.kerosene.kfe.rail.CustodyGateway;
import com.kerosene.kfe.rail.LightningInvoiceGateway;
import com.kerosene.kfe.repository.KfeBalanceMovementRepository;
import com.kerosene.kfe.repository.KfeExecutionOutboxRepository;
import com.kerosene.kfe.repository.KfeIdempotencyRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.service.KfeBalanceMetrics;
import com.kerosene.kfe.service.KfeBalanceService;
import com.kerosene.kfe.service.KfeColdWalletObservationService;
import com.kerosene.kfe.service.KfeDashboardPublisher;
import com.kerosene.kfe.service.KfeExecutionTransactionHelper;
import com.kerosene.kfe.service.KfeFeeSettlementService;
import com.kerosene.kfe.service.KfeFinancialMetrics;
import com.kerosene.kfe.service.KfeHashService;
import com.kerosene.kfe.service.KfeInboundSettlementService;
import com.kerosene.kfe.service.KfeNetworkMonitor;
import com.kerosene.kfe.service.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.service.KfeOutboundConfirmationMonitor;
import com.kerosene.kfe.service.KfeResponseMapper;
import com.kerosene.kfe.service.KfeStatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real guard, mocked durable store and financial/provider boundaries; no live RPC or signers. */
class KfeNetworkSettlementMaintenanceTest {
    private enum Root {
        SETTLE, NETWORK_ONCHAIN, NETWORK_LIGHTNING,
        OUTBOUND_OPEN, OUTBOUND_SETTLED, INBOUND_CONFIRMATIONS, COLD_OUTBOUND, COLD_INBOUND
    }
    private enum Denial { DRAIN, OUTAGE, UNINJECTED }

    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0L);
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicLong uncertain = new AtomicLong();
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeExecutionOutboxRepository outboxes = mock(KfeExecutionOutboxRepository.class);
    private final KfeBalanceMovementRepository movements = mock(KfeBalanceMovementRepository.class);
    private final KfeIdempotencyRepository idempotency = mock(KfeIdempotencyRepository.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final KfeStatementService statements = mock(KfeStatementService.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboard = mock(KfeDashboardPublisher.class);
    private final KfeHashService hash = mock(KfeHashService.class);
    private final FinancialNotificationPort notifications = mock(FinancialNotificationPort.class);
    private final KfeFeeSettlementService fees = mock(KfeFeeSettlementService.class);
    private final ObjectProvider<KfeOnchainBalanceSyncService> syncProvider = provider();
    private final ObjectProvider<KfeBalanceMetrics> balanceMetrics = provider();
    private final ObjectProvider<BlockchainClient> chainProvider = provider();
    private final ObjectProvider<LightningInvoiceGateway> invoiceProvider = provider();
    private final ObjectProvider<BitcoinCoreRpcClient> coreProvider = provider();
    private final ObjectProvider<KfeColdWalletObservationService> coldProvider = provider();
    private final BlockchainClient chain = mock(BlockchainClient.class);
    private final LightningInvoiceGateway invoices = mock(LightningInvoiceGateway.class);
    private final BitcoinCoreRpcClient core = mock(BitcoinCoreRpcClient.class);
    private final KfeColdWalletObservationService cold = mock(KfeColdWalletObservationService.class);
    private final KfeExecutionTransactionHelper helper = mock(KfeExecutionTransactionHelper.class);
    private final KfeFinancialMetrics financialMetrics = mock(KfeFinancialMetrics.class);
    private final ObjectMapper json = new ObjectMapper();
    private final KfeTransactionEntity tx = spy(new KfeTransactionEntity());
    private final KfeExecutionOutboxEntity outbox = spy(new KfeExecutionOutboxEntity());
    private final String txid = "ab".repeat(32);
    private final KfeInboundSettlementService settlement = newSettlement();
    private final KfeNetworkMonitor network = new KfeNetworkMonitor(
            outboxes, transactions, settlement, chainProvider, invoiceProvider, json, 50, finality());
    private final KfeOutboundConfirmationMonitor confirmations = new KfeOutboundConfirmationMonitor(
            transactions, helper, coreProvider, coldProvider, financialMetrics, 50, finality(), 5, 300);
    private KfeInboundSettlementService.InboundSettlementProof proof;

    @BeforeEach
    void activeFixture() {
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
                new KfeMaintenanceStore.Control(draining.get() ? KfeMaintenanceGuard.Mode.DRAINING
                        : KfeMaintenanceGuard.Mode.ACTIVE, "network-maintenance", 1L),
                Instant.now(), Map.of("admissionsUncertain", uncertain.get())));
        inject();
        tx.setUserId(99L);
        tx.setDestinationWalletId(UUID.randomUUID());
        tx.setSourceWalletId(UUID.randomUUID());
        tx.setGrossAmountSats(1000L);
        tx.setReceiverAmountSats(1000L);
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.INBOUND);
        tx.setBlockchainTxid(txid);
        tx.setPaymentHash("financial-payment-hash");
        tx.setProviderReference("financial-provider-reference");
        tx.setIdempotencyKey("financial-idempotency-key");
        outbox.setTransactionId(tx.getId());
        outbox.setStatus("REQUIRES_RECONCILIATION");
        outbox.setOperation("ONCHAIN_INBOUND");
        outbox.setProviderReference("financial-provider-reference");
        outbox.setClaimToken(UUID.randomUUID());
        outbox.setClaimedBy("prior-worker");
        outbox.setAttempts(2);
        outbox.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC));
        outbox.setPayloadJson("{\"externalReference\":\"lnbc123\"}");
        proof = new KfeInboundSettlementService.InboundSettlementProof(tx.getId(), outbox.getId(),
                "MONITOR", "financial-provider-reference", txid, 1000L, 3, "raw-proof");
        when(outboxes.findByIdForUpdate(outbox.getId())).thenReturn(Optional.of(outbox));
        when(transactions.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));
        when(transactions.findById(tx.getId())).thenReturn(Optional.of(tx));
        when(outboxes.findInboundReconciliationCandidates(anyList(), any())).thenReturn(List.of(outbox));
        when(mapper.buildDisplayPayload(any(), anyLong())).thenReturn(Map.of("status", "SETTLED"));
        when(hash.sha256(anyString())).thenReturn("hash");
        when(chainProvider.getIfAvailable()).thenReturn(chain);
        when(invoiceProvider.getIfAvailable()).thenReturn(invoices);
        when(coreProvider.getIfAvailable()).thenReturn(core);
        when(coldProvider.getIfAvailable()).thenReturn(cold);
        when(invoices.isLive()).thenReturn(true);
        when(invoices.providerName()).thenReturn("INVOICE_MONITOR");
        when(invoices.getLightningInvoiceStatus(any())).thenReturn(
                new CustodyGateway.IncomingLightningInvoiceStatus("SETTLED", 1000L,
                        LocalDateTime.now(ZoneOffset.UTC), "invoice-proof"));
        when(chain.getRawTransaction(txid, true)).thenReturn(
                json.createObjectNode().put("confirmations", 3).put("sats", 1000));
        when(core.fetchTransactionChainStatus(txid)).thenReturn(chainStatus(3));
        when(helper.settleOutboundWhenConfirmed(any(), anyInt())).thenReturn(true);
    }

    private static Stream<Arguments> deniedRoots() {
        return Arrays.stream(Root.values()).flatMap(root -> Arrays.stream(Denial.values())
                .map(denial -> Arguments.of(root, denial)));
    }

    @ParameterizedTest
    @MethodSource("deniedRoots")
    void denialStopsBeforeProbeOrManagedEntityChangesAndPreservesPendingWork(Root root, Denial denial) {
        prepare(root);
        if (denial == Denial.DRAIN) {
            draining.set(true);
        } else if (denial == Denial.OUTAGE) {
            when(store.admit(anyString())).thenThrow(new IllegalStateException("store unavailable"));
        } else {
            settlement.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
            network.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
            confirmations.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
        }
        clearInvocations(tx, outbox);

        if (root == Root.SETTLE) {
            assertThatThrownBy(() -> run(root)).isInstanceOfSatisfying(
                    KfeMaintenanceGuard.MaintenanceException.class,
                    failure -> assertThat(failure.httpStatus()).isEqualTo(503));
        } else {
            assertThatCode(() -> run(root)).doesNotThrowAnyException();
        }

        // Even setter-only dirty checking would be an effect; neither entity is inspected on denial.
        verifyNoInteractions(tx, outbox, chain, invoices, helper, cold, financialMetrics,
                movements, idempotency, wallets, balances, audit, statements, mapper, dashboard,
                hash, notifications, fees, syncProvider, balanceMetrics);
        verify(core, never()).fetchTransactionChainStatus(anyString());
        verify(core, never()).findReplacementTxid(anyString());
        verify(core, never()).getRawTransaction(anyString(), anyBoolean());
        verify(core, never()).queryOutpoint(anyString(), anyInt());
        verify(transactions, never()).findById(any());
        verify(transactions, never()).findByIdForUpdate(any());
        verify(transactions, never()).save(any());
        verify(outboxes, never()).findByIdForUpdate(any());
        verify(outboxes, never()).save(any());
        verify(store, never()).resolve(any(), anyBoolean());
        verify(store, times(denial == Denial.UNINJECTED ? 0 : 1)).admit(anyString());
    }

    @Test
    void compatibilityConstructionIsUnavailableAndSpringInjectionIsMandatory() throws Exception {
        KfeInboundSettlementService uninjected = newSettlement();
        assertThatThrownBy(() -> uninjected.settle(proof))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        KfeNetworkMonitor uninjectedNetwork = new KfeNetworkMonitor(
                outboxes, transactions, settlement, chainProvider, invoiceProvider, json, 50, finality());
        uninjectedNetwork.reconcileInbound();
        KfeOutboundConfirmationMonitor uninjectedConfirmations = new KfeOutboundConfirmationMonitor(
                transactions, helper, coreProvider, coldProvider, financialMetrics, 50, finality(), 5, 300);
        prepare(Root.OUTBOUND_OPEN);
        uninjectedConfirmations.reconcileOutboundConfirmations();
        verifyNoInteractions(chain, invoices, helper, movements, balances, notifications, dashboard);
        verify(core, never()).fetchTransactionChainStatus(anyString());
        verify(store, never()).admit(anyString());
        for (Class<?> type : List.of(KfeInboundSettlementService.class,
                KfeNetworkMonitor.class, KfeOutboundConfirmationMonitor.class)) {
            Autowired annotation = type.getMethod("setMaintenanceGuard", KfeMaintenanceGuard.class)
                    .getAnnotation(Autowired.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.required()).isTrue();
        }
        assertThatThrownBy(() -> settlement.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> network.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> confirmations.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void activeRootsKeepFinancialResultsButAlwaysResolveUncertain(Root root) {
        prepare(root);
        run(root);
        verifyEffects(root);
        verify(store).admit(operation(root));
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void synchronousNestedRootCanFinishDuringDrainWithoutNewAdmission(Root root) {
        prepare(root);
        guard.executeMutation("admitted.parent", () -> {
            draining.set(true);
            run(root);
            return Boolean.TRUE;
        });
        verifyEffects(root);
        verify(store).admit("admitted.parent");
        verify(store, times(1)).admit(anyString());
        // Child uncertainty propagates even when the parent's own work returns successfully.
        verify(store).resolve(admission.id(), false);
        assertThat(guard.status().blockers()).containsEntry("admissionsUncertain", 1L);
        assertThat(guard.status().safeToUpdate()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Root.class, names = {"NETWORK_ONCHAIN", "NETWORK_LIGHTNING"})
    void providerMayStartDrainAndAlreadyAdmittedSettlementStillFinishes(Root root) {
        prepare(root);
        if (root == Root.NETWORK_ONCHAIN) {
            when(chain.getRawTransaction(txid, true)).thenAnswer(invocation -> {
                draining.set(true);
                return json.createObjectNode().put("confirmations", 3).put("sats", 1000);
            });
        } else {
            when(invoices.getLightningInvoiceStatus(any())).thenAnswer(invocation -> {
                draining.set(true);
                return new CustodyGateway.IncomingLightningInvoiceStatus("SETTLED", 1000L,
                        LocalDateTime.now(ZoneOffset.UTC), "invoice-proof");
            });
        }
        run(root);
        verifyEffects(root);
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void inboundSchedulerStopsAtFirstDeniedCandidateWithoutMarkingPendingRowsFailed() {
        KfeExecutionOutboxEntity later = spy(new KfeExecutionOutboxEntity());
        later.setTransactionId(UUID.randomUUID());
        when(outboxes.findInboundReconciliationCandidates(anyList(), any())).thenReturn(List.of(outbox, later));
        draining.set(true);
        clearInvocations(outbox, later);
        network.reconcileInbound();
        verify(store, times(1)).admit("network.inbound-inspect");
        verifyNoInteractions(outbox, later, transactions, chain, invoices, movements, balances);
        verify(outboxes, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = Root.class, names = {"OUTBOUND_OPEN", "OUTBOUND_SETTLED", "INBOUND_CONFIRMATIONS"})
    void confirmationSchedulerStopsAtFirstDeniedCandidate(Root root) {
        prepare(root);
        KfeTransactionEntity later = spy(new KfeTransactionEntity());
        later.setBlockchainTxid("cd".repeat(32));
        List<KfeTransactionStatus> statuses = root == Root.OUTBOUND_SETTLED
                ? List.of(KfeTransactionStatus.SETTLED) : root == Root.INBOUND_CONFIRMATIONS
                ? inboundStatuses() : openStatuses();
        KfeDirection direction = root == Root.INBOUND_CONFIRMATIONS ? KfeDirection.INBOUND : KfeDirection.OUTBOUND;
        when(transactions.findOutboundAwaitingConfirmation(eq(KfeRail.ONCHAIN), eq(direction),
                eq(statuses), eq(6), any())).thenReturn(List.of(tx, later));
        draining.set(true);
        clearInvocations(tx, later);
        confirmations.reconcileOutboundConfirmations();
        verify(store, times(1)).admit(operation(root));
        verifyNoInteractions(tx, later, core, helper, cold, financialMetrics);
        verify(transactions, never()).save(any());
        if (direction == KfeDirection.OUTBOUND) {
            verify(transactions, never()).findOutboundAwaitingConfirmation(eq(KfeRail.ONCHAIN),
                    eq(KfeDirection.INBOUND), anyList(), anyInt(), any());
        }
    }

    @Test
    void successfulFirstCandidateDoesNotAuthorizeNextCandidateAfterDrain() {
        prepare(Root.OUTBOUND_OPEN);
        KfeTransactionEntity later = spy(new KfeTransactionEntity());
        later.setBlockchainTxid("cd".repeat(32));
        when(transactions.findOutboundAwaitingConfirmation(eq(KfeRail.ONCHAIN), eq(KfeDirection.OUTBOUND),
                eq(openStatuses()), eq(6), any())).thenReturn(List.of(tx, later));
        when(core.fetchTransactionChainStatus(txid)).thenAnswer(invocation -> {
            draining.set(true);
            return chainStatus(3);
        });
        clearInvocations(later);
        confirmations.reconcileOutboundConfirmations();
        verify(helper).settleOutboundWhenConfirmed(tx.getId(), 3);
        verifyNoInteractions(later);
        verify(core, times(1)).fetchTransactionChainStatus(anyString());
        verify(store, times(2)).admit("network.outbound-confirmations");
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest
    @ValueSource(ints = {TransactionSynchronization.STATUS_COMMITTED,
            TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_UNKNOWN})
    void transactionCompletionNeverManufacturesCertainSettlement(int completion) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(settlement.settle(proof)).isTrue();
            verify(store, never()).resolve(any(), anyBoolean());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(completion);
            }
            verify(store).resolve(admission.id(), false);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void swallowedNotificationFailurePreservesSettlementAndUncertainty() {
        doThrow(new IllegalStateException("delivery unknown")).when(notifications)
                .notifyDepositConfirmed(anyLong(), any(), any(), anyString(), anyLong(), anyInt());
        assertThat(settlement.settle(proof)).isTrue();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        verify(dashboard).publishAfterCommit(99L);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void probeFailureLeavesUncertaintyAndDoesNotSettle() {
        when(core.fetchTransactionChainStatus(txid)).thenThrow(new IllegalStateException("RPC unknown"));
        prepare(Root.OUTBOUND_OPEN);
        confirmations.reconcileOutboundConfirmations();
        verifyNoInteractions(helper);
        verify(store).resolve(admission.id(), false);
        assertThat(tx.getLastChainProbeAt()).isNull();
        verify(transactions, never()).save(any());
    }

    @Test
    void drainStatusKeepsStaticCoverageBlockersEvenWhenMockStoreReportsNoPendingAdmissions() {
        draining.set(true);
        KfeMaintenanceGuard.Status status = guard.status();
        assertThat(status.blockers()).containsEntry("mutationCoverageUnknown", 1L)
                .containsEntry("callbackCoverageUnknown", 1L)
                .containsEntry("readSideEffectsUnknown", 1L);
        assertThat(status.safeToUpdate()).isFalse();
    }

    private void inject() {
        settlement.setMaintenanceGuard(guard);
        network.setMaintenanceGuard(guard);
        confirmations.setMaintenanceGuard(guard);
    }

    private void prepare(Root root) {
        if (root == Root.NETWORK_LIGHTNING) {
            tx.setRail(KfeRail.LIGHTNING);
        }
        if (root == Root.OUTBOUND_OPEN || root == Root.OUTBOUND_SETTLED || root == Root.COLD_OUTBOUND) {
            tx.setDirection(KfeDirection.OUTBOUND);
        }
        if (root == Root.OUTBOUND_SETTLED) {
            tx.setStatus(KfeTransactionStatus.SETTLED);
        }
        if (root == Root.COLD_OUTBOUND || root == Root.COLD_INBOUND) {
            tx.setProvider(KfeColdWalletObservationService.PROVIDER_COLD_OBSERVER);
        }
        when(transactions.findOutboundAwaitingConfirmation(eq(KfeRail.ONCHAIN), eq(KfeDirection.OUTBOUND),
                eq(openStatuses()), eq(6), any())).thenReturn(
                root == Root.OUTBOUND_OPEN || root == Root.COLD_OUTBOUND ? List.of(tx) : List.of());
        when(transactions.findOutboundAwaitingConfirmation(eq(KfeRail.ONCHAIN), eq(KfeDirection.OUTBOUND),
                eq(List.of(KfeTransactionStatus.SETTLED)), eq(6), any())).thenReturn(
                root == Root.OUTBOUND_SETTLED ? List.of(tx) : List.of());
        when(transactions.findOutboundAwaitingConfirmation(eq(KfeRail.ONCHAIN), eq(KfeDirection.INBOUND),
                eq(inboundStatuses()), eq(6), any())).thenReturn(
                root == Root.INBOUND_CONFIRMATIONS || root == Root.COLD_INBOUND ? List.of(tx) : List.of());
    }

    private void run(Root root) {
        switch (root) {
            case SETTLE -> assertThat(settlement.settle(proof)).isTrue();
            case NETWORK_ONCHAIN, NETWORK_LIGHTNING -> network.reconcileInbound();
            default -> confirmations.reconcileOutboundConfirmations();
        }
    }

    private void verifyEffects(Root root) {
        switch (root) {
            case SETTLE, NETWORK_ONCHAIN, NETWORK_LIGHTNING -> {
                assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
                assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
                verify(balances).creditAvailable(tx.getDestinationWalletId(), "BTC", 1000L);
                verify(fees).creditKeroseneFee(tx);
                verify(dashboard).publishAfterCommit(99L);
            }
            case OUTBOUND_OPEN -> {
                var order = inOrder(helper);
                order.verify(helper).touchOutboundConfirmations(tx.getId(), 3, "block", 100);
                order.verify(helper).settleOutboundWhenConfirmed(tx.getId(), 3);
            }
            case OUTBOUND_SETTLED, INBOUND_CONFIRMATIONS -> {
                verify(helper).touchOutboundConfirmations(tx.getId(), 3, "block", 100);
                verify(helper, never()).settleOutboundWhenConfirmed(any(), anyInt());
            }
            case COLD_OUTBOUND, COLD_INBOUND -> {
                verify(cold).touchColdConfirmations(tx.getId(), 3);
                verify(helper, never()).settleOutboundWhenConfirmed(any(), anyInt());
            }
        }
    }

    private static String operation(Root root) {
        return switch (root) {
            case SETTLE -> "network.inbound-settle";
            case NETWORK_ONCHAIN, NETWORK_LIGHTNING -> "network.inbound-inspect";
            case INBOUND_CONFIRMATIONS, COLD_INBOUND -> "network.inbound-confirmations";
            default -> "network.outbound-confirmations";
        };
    }

    private KfeInboundSettlementService newSettlement() {
        return new KfeInboundSettlementService(transactions, outboxes, movements, idempotency,
                wallets, balances, audit, statements, mapper, dashboard, hash, notifications, fees,
                syncProvider, balanceMetrics);
    }

    private static List<KfeTransactionStatus> openStatuses() {
        return List.of(KfeTransactionStatus.EXECUTING, KfeTransactionStatus.VALIDATING,
                KfeTransactionStatus.REQUIRES_RECONCILIATION);
    }

    private static List<KfeTransactionStatus> inboundStatuses() {
        return List.of(KfeTransactionStatus.VALIDATING, KfeTransactionStatus.EXECUTING,
                KfeTransactionStatus.SETTLED);
    }

    private static BitcoinCoreRpcClient.TransactionChainStatus chainStatus(int confirmations) {
        return new BitcoinCoreRpcClient.TransactionChainStatus(
                BitcoinCoreRpcClient.TransactionChainStatus.ChainState.CONFIRMED,
                confirmations, "block", 100, null);
    }

    private static KfeBitcoinFinalityPolicy finality() {
        KfeBitcoinFinalityPolicy policy = new KfeBitcoinFinalityPolicy();
        policy.setCreditConfirmations(1);
        policy.setFinalityConfirmations(6);
        policy.setReorgMonitorConfirmations(12);
        return policy;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }
}
