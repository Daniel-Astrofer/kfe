package com.kerosene.kfe.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.FinancialNotificationPort;
import com.kerosene.common.security.StringColumnCryptoPort;
import com.kerosene.kfe.application.transaction.KfeBalanceMovementRecorder;
import com.kerosene.kfe.application.transaction.KfePlatformOnchainDestinationRouter;
import com.kerosene.kfe.config.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.model.*;
import com.kerosene.kfe.repository.*;
import com.kerosene.kfe.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfePreparedPeerMaintenanceTest {
    private enum Root { PREPARED, PEER }
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeExecutionOutboxRepository outbox = mock(KfeExecutionOutboxRepository.class);
    private final StringColumnCryptoPort crypto = mock(StringColumnCryptoPort.class);
    private final KfeHashService hashes = mock(KfeHashService.class);
    private final KfePlatformOnchainDestinationRouter router = mock(KfePlatformOnchainDestinationRouter.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeBalanceMovementRepository movements = mock(KfeBalanceMovementRepository.class);
    private final KfePaymentRequestRepository requests = mock(KfePaymentRequestRepository.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRecorder recorder = mock(KfeBalanceMovementRecorder.class);
    private final KfePricingService pricing = mock(KfePricingService.class);
    private final KfeFeeSettlementService fees = mock(KfeFeeSettlementService.class);
    private final KfeStatementService statements = mock(KfeStatementService.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboard = mock(KfeDashboardPublisher.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final FinancialNotificationPort notifications = mock(FinancialNotificationPort.class);
    private final KfeTransactionEntity outbound = new KfeTransactionEntity();
    private final UUID outboxId = UUID.randomUUID(), claimToken = UUID.randomUUID();
    private final KfeMaintenanceStore.Admission admission = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private KfePreparedExecutionService prepared;
    private KfePlatformPeerInboundService peer;

    @BeforeEach void setup() {
        prepared = new KfePreparedExecutionService(outbox, crypto, hashes, new ObjectMapper());
        @SuppressWarnings("unchecked") ObjectProvider<FinancialNotificationPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(notifications);
        peer = new KfePlatformPeerInboundService(router, wallets, transactions, movements, requests,
                balances, recorder, pricing, fees, statements, mapper, dashboard, audit, provider, new KfeBitcoinFinalityPolicy());
        outbound.setRail(KfeRail.ONCHAIN); outbound.setDirection(KfeDirection.OUTBOUND);
        outbound.setExternalReference("synthetic-address"); outbound.setBlockchainTxid("synthetic-txid");
        prepared.setMaintenanceGuard(guard); peer.setMaintenanceGuard(guard);
        when(store.admit(anyString())).thenReturn(admission);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void drainingRejectsBeforeLocksEncryptionRoutingOrFinancialEffects(Root root) {
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain"));
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects(); verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest @EnumSource(Root.class)
    void unavailableInjectionRejectsWithoutEffects(Root root) {
        prepared.setMaintenanceGuard(KfeMaintenanceGuard.unavailable()); peer.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects(); verifyNoInteractions(store);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void storageOutageCannotStartWork(Root root) {
        when(store.admit(anyString())).thenThrow(new IllegalStateException("synthetic outage"));
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects();
    }

    @Test void ownedPayloadLookupIsReadOnlyAndDoesNotInventDurableChildProvenance() {
        prepared.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
        var row = new KfeExecutionOutboxEntity(); row.setTransactionId(outbound.getId());
        row.setOperation("ONCHAIN_OUTBOUND"); row.setStatus("PROCESSING"); row.setClaimToken(claimToken);
        row.setLeaseExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        when(outbox.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        assertThat(prepared.load(row.getId(), outbound.getId(), claimToken, "ONCHAIN_OUTBOUND",
                KfePreparedExecutionService.PayloadType.ONCHAIN, String.class)).isEmpty();
        verify(outbox, never()).saveAndFlush(any()); verifyNoInteractions(store, crypto, hashes);
    }

    @Test void invalidPeerIsNoopWhileEligibleParentCanFinishDuringDrain() {
        peer.exposeAfterOutboundBroadcast(null); verifyNoInteractions(store); noEffects();
        guard.executeMutation("synthetic.parent", () -> {
            when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain"));
            peer.exposeAfterOutboundBroadcast(outbound); return null;
        });
        verify(store, times(1)).admit(anyString());
        verify(router).findPlatformSinkWalletIdForAddress("synthetic-address");
        verify(store).resolve(admission.id(), false);
        verifyNoInteractions(balances, transactions, notifications);
    }

    private void noEffects() { verifyNoInteractions(outbox, crypto, hashes, router, wallets, transactions, movements,
            requests, balances, recorder, pricing, fees, statements, mapper, dashboard, audit, notifications); }
    private void invoke(Root root) {
        if (root == Root.PEER) peer.exposeAfterOutboundBroadcast(outbound);
        else prepared.persistIfAbsent(outboxId, outbound.getId(), claimToken, "ONCHAIN_OUTBOUND",
                KfePreparedExecutionService.PayloadType.ONCHAIN, "synthetic-payload", "synthetic-reference", String.class);
    }
}
