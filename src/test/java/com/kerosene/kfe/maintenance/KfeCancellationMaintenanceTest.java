package com.kerosene.kfe.maintenance;

import com.kerosene.kfe.model.*;
import com.kerosene.kfe.rail.LightningInvoiceGateway;
import com.kerosene.kfe.repository.*;
import com.kerosene.kfe.service.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeCancellationMaintenanceTest {
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfePaymentRequestRepository requests = mock(KfePaymentRequestRepository.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeLightningLiquidityService liquidity = mock(KfeLightningLiquidityService.class);
    private final KfeStatementService statements = mock(KfeStatementService.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboard = mock(KfeDashboardPublisher.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final LightningInvoiceGateway invoices = mock(LightningInvoiceGateway.class);
    private final KfeTransactionCancellationService service = new KfeTransactionCancellationService(
            transactions, requests, balances, liquidity, statements, mapper, dashboard, audit, invoices);

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void defaultUnavailableAndDrainCannotCancelInvoiceOrChangeEntities() {
        assertThatThrownBy(() -> service.cancelPaymentRequest(7L, UUID.randomUUID()))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(requests, transactions, invoices, balances, liquidity, statements, dashboard, audit);
        service.setMaintenanceGuard(guard);
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
        assertThatThrownBy(() -> service.cancelPaymentRequest(7L, UUID.randomUUID()))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(requests, transactions, invoices, balances, liquidity, statements, dashboard, audit);
    }

    @Test
    void transactionValidationMayReadButDrainPrecedesReserveAndStatusChanges() {
        service.setMaintenanceGuard(guard);
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(7L); tx.setStatus(KfeTransactionStatus.LOCKED);
        tx.setRail(KfeRail.ONCHAIN); tx.setDirection(KfeDirection.OUTBOUND);
        tx.setSourceWalletId(UUID.randomUUID()); tx.setTotalDebitSats(100L);
        when(transactions.findParticipantVisibleById(tx.getId(), 7L, KfeRail.INTERNAL, KfeDirection.INTERNAL))
                .thenReturn(Optional.of(tx));
        assertThatThrownBy(() -> service.cancelTransaction(7L, tx.getId()))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.LOCKED);
        verify(transactions, never()).save(any());
        verifyNoInteractions(invoices, balances, liquidity, statements, dashboard, audit);
    }

    @Test
    void alreadyAdmittedNestedCancellationCanFinishButNextRootCannotReuseIt() {
        service.setMaintenanceGuard(guard);
        KfePaymentRequestEntity request = request();
        when(requests.findByIdAndUserId(request.getId(), 7L)).thenReturn(Optional.of(request));
        when(requests.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var admission = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
        when(store.admit("parent")).thenReturn(admission);
        guard.executeMutation("parent", () -> {
            doThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining")).when(store).admit(anyString());
            return service.cancelPaymentRequest(7L, request.getId());
        });
        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.CANCELLED);
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        assertThatThrownBy(() -> service.cancelPaymentRequest(7L, request.getId()))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
    }

    @Test
    void swallowedProviderFailureAndParentCommitCannotCertifyCancellationCompletion() {
        service.setMaintenanceGuard(guard);
        var admission = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
        when(store.admit(anyString())).thenReturn(admission);
        KfePaymentRequestEntity request = request();
        when(requests.findByIdAndUserId(request.getId(), 7L)).thenReturn(Optional.of(request));
        when(requests.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoices.cancelLightningInvoice(any())).thenThrow(new IllegalStateException("synthetic timeout"));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        service.cancelPaymentRequest(7L, request.getId());
        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.CANCELLED);
        verify(store, never()).resolve(any(), anyBoolean());
        TransactionSynchronizationManager.getSynchronizations().forEach(callback ->
                callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(admission.id(), true);
    }

    private KfePaymentRequestEntity request() {
        KfePaymentRequestEntity request = new KfePaymentRequestEntity();
        request.setUserId(7L); request.setWalletId(UUID.randomUUID());
        request.setPublicId("synthetic-cancel-capability"); request.setStatus(KfePaymentRequestStatus.OPEN);
        request.setRail(KfeRail.LIGHTNING); request.setPaymentHash("synthetic-payment-hash");
        return request;
    }
}
