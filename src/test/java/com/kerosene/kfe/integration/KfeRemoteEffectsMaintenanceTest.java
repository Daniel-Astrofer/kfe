package com.kerosene.kfe.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.common.financial.DeviceProof;
import com.kerosene.common.financial.PasskeyAssertion;
import com.kerosene.common.financial.RecoveryApproval;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;
import com.kerosene.kfe.maintenance.KfeMaintenanceService;
import com.kerosene.kfe.maintenance.KfeMaintenanceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeRemoteEffectsMaintenanceTest {
    enum Root {
        LOCAL_FACTOR, CUSTODY_TRANSFER, WALLET_OUTBOUND, COLD_PSBT,
        DEPOSIT_CONFIRMED, REQUEST_DEPOSIT_CONFIRMED, DEPOSIT_DETECTED, DEPOSIT_PROGRESS,
        OUTBOUND_DETECTED, OUTBOUND_CONFIRMED, INTERNAL_RECEIVED, INTERNAL_SENT,
        EXTERNAL_SENT, PAYMENT_INITIATED, PAYMENT_BROADCAST, PAYMENT_CONFIRMED,
        PAYMENT_FAILED, RECONCILIATION_REQUIRED;

        boolean approval() { return ordinal() <= COLD_PSBT.ordinal(); }
    }

    enum AdmissionState { ACTIVE, DRAINING, UNAVAILABLE, STORAGE_ERROR }

    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceGuard guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final RestTemplate transport = mock(RestTemplate.class);
    private final KfeRemoteFinancialTransactionApprovalClient approvals = approvals("credential");
    private final KfeRemoteFinancialNotificationClient notifications = notifications("credential");
    private final UUID transactionId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @ParameterizedTest @EnumSource(Root.class)
    void drainRejectsEveryTypedRootBeforeTransport(Root root) {
        inject();
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class)
                .hasMessage("draining");
        verifyNoInteractions(transport);
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest @EnumSource(Root.class)
    void missingInjectionRejectsEveryTypedRootBeforeTransport(Root root) {
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(transport, store);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void storageOutageRejectsEveryTypedRootBeforeTransport(Root root) {
        inject();
        when(store.admit(anyString())).thenThrow(new IllegalStateException("synthetic storage outage"));
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class)
                .hasMessage("KFE maintenance admission is unavailable.");
        verifyNoInteractions(transport);
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest @EnumSource(Root.class)
    void activeDirectSuccessAdmitsBeforeTransportAndRemainsUncertain(Root root) {
        active();
        invoke(root);
        var order = inOrder(store, transport);
        order.verify(store).admit(operation(root));
        order.verify(transport).postForEntity(eq(url(root)), any(HttpEntity.class), eq(Void.class));
        order.verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @ParameterizedTest @EnumSource(Root.class)
    void parentCommitCannotCompleteRemoteSuccess(Root root) {
        active();
        beginTransaction();
        guard.executeMutation("synthetic.parent", () -> { invoke(root); return null; });
        verify(store, times(1)).admit("synthetic.parent");
        verify(store, never()).resolve(any(), anyBoolean());
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @ParameterizedTest @EnumSource(Root.class)
    void rollbackAndUnknownOutcomeCannotCompleteRemoteSuccess(Root root) {
        active();
        for (int status : new int[]{TransactionSynchronization.STATUS_ROLLED_BACK,
                TransactionSynchronization.STATUS_UNKNOWN}) {
            beginTransaction();
            invoke(root);
            verify(store, never()).resolve(any(), anyBoolean());
            finishTransaction(status);
            verify(store).resolve(admission.id(), false);
            clearInvocations(store, transport);
        }
    }

    @ParameterizedTest @EnumSource(Root.class)
    void admittedParentCanFinishDuringDrainButFreshCallIsRejected(Root root) {
        active();
        beginTransaction();
        guard.executeMutation("synthetic.parent", () -> {
            when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
            invoke(root);
            return null;
        });
        verify(store, times(1)).admit(anyString());
        verify(transport).postForEntity(eq(url(root)), any(HttpEntity.class), eq(Void.class));
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), false);
        clearInvocations(store, transport);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(transport);
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest @EnumSource(Root.class)
    void httpRejectionKeepsApprovalMappingAndNotificationBestEffortButRemainsUncertain(Root root) {
        active();
        var failure = HttpClientErrorException.create(HttpStatus.PRECONDITION_REQUIRED, "challenge", null,
                "{\"message\":\"challenge\",\"errorCode\":\"AUTH_012\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(transport.postForEntity(anyString(), any(HttpEntity.class), eq(Void.class))).thenThrow(failure);
        beginTransaction();
        guard.executeMutation("synthetic.parent", () -> {
            if (root.approval()) {
                assertThatThrownBy(() -> invoke(root)).isInstanceOf(StructuredPlatformException.class)
                        .hasMessage("challenge");
            } else {
                assertThatCode(() -> invoke(root)).doesNotThrowAnyException();
            }
            return null;
        });
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(transport).postForEntity(eq(url(root)), any(HttpEntity.class), eq(Void.class));
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @ParameterizedTest @EnumSource(Root.class)
    void transportOutageKeepsExistingErrorContractAndRemainsUncertain(Root root) {
        active();
        var failure = new ResourceAccessException("synthetic timeout");
        when(transport.postForEntity(anyString(), any(HttpEntity.class), eq(Void.class))).thenThrow(failure);
        if (root.approval()) {
            assertThatThrownBy(() -> invoke(root)).isSameAs(failure);
        } else {
            assertThatCode(() -> invoke(root)).doesNotThrowAnyException();
        }
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @ParameterizedTest @EnumSource(Root.class)
    void transportMaintenanceRejectionIsNeverSwallowed(Root root) {
        active();
        var failure = new KfeMaintenanceGuard.MaintenanceException(503, "nested admission rejected");
        when(transport.postForEntity(anyString(), any(HttpEntity.class), eq(Void.class))).thenThrow(failure);
        assertThatThrownBy(() -> invoke(root)).isSameAs(failure);
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void unobservableTransactionRejectsBeforeTransport(Root root) {
        active();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(transport);
    }

    @ParameterizedTest @EnumSource(AdmissionState.class)
    void outboundConflictedIsLocalValidationFailureBeforeAdmissionOrTransport(AdmissionState state) {
        switch (state) {
            case ACTIVE -> active();
            case DRAINING -> {
                inject();
                when(store.admit(anyString())).thenThrow(
                        new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
            }
            case STORAGE_ERROR -> {
                inject();
                when(store.admit(anyString())).thenThrow(new IllegalStateException("synthetic storage outage"));
            }
            case UNAVAILABLE -> { }
        }
        // Preserve the existing -1 payload policy and its rejection by the request contract.
        assertThatThrownBy(() -> notifications.notifyOutboundConflicted(
                42L, transactionId, walletId, "ONCHAIN", 100, "txid"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("confirmations must be >= 0, got: -1");
        verifyNoInteractions(store, transport);
    }

    @Test
    void settersAreMandatoryAndRejectNullWithoutReplacingTheInstalledGuard() throws Exception {
        for (Class<?> type : new Class<?>[]{approvals.getClass(), notifications.getClass()}) {
            var annotation = type.getMethod("setMaintenanceGuard", KfeMaintenanceGuard.class)
                    .getAnnotation(Autowired.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.required()).isTrue();
        }
        active();
        assertThatThrownBy(() -> approvals.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> notifications.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
        invoke(Root.LOCAL_FACTOR);
        invoke(Root.DEPOSIT_CONFIRMED);
        verify(store, times(2)).resolve(admission.id(), false);
    }

    @Test
    void unsupportedLegacyApprovalsAndMissingCredentialsStayPureValidation() {
        assertThatThrownBy(() -> approvals.approveLocalFactor(42L, "device", "proof"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> approvals.approveCustodyTransfer(42L, "proof"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> approvals.approveWalletOutbound(41L, 42L, "a", "b", "c"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> approvals.approveColdWalletPsbt(42L, "proof"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> approvals("").approveColdWalletPsbt(42L, deviceProof()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> notifications("").notifyPaymentInitiated(42L, transactionId, walletId,
                "ONCHAIN", 100)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(transport, store);
    }

    private void inject() {
        approvals.setMaintenanceGuard(guard);
        notifications.setMaintenanceGuard(guard);
    }

    private void active() {
        inject();
        when(store.admit(anyString())).thenReturn(admission);
    }

    private KfeRemoteFinancialTransactionApprovalClient approvals(String secret) {
        var client = new KfeRemoteFinancialTransactionApprovalClient(new RestTemplateBuilder(), new ObjectMapper(),
                "http://server.test", secret, 100, 100);
        ReflectionTestUtils.setField(client, "restTemplate", transport);
        return client;
    }

    private KfeRemoteFinancialNotificationClient notifications(String secret) {
        var client = new KfeRemoteFinancialNotificationClient(new RestTemplateBuilder(), "http://server.test",
                secret, 100, 100);
        ReflectionTestUtils.setField(client, "restTemplate", transport);
        return client;
    }

    private void invoke(Root root) {
        var passkey = new PasskeyAssertion("credential", "client-data", "authenticator-data", "signature", "42");
        var recovery = new RecoveryApproval("proof", "challenge", Instant.parse("2026-07-27T12:00:00Z"));
        switch (root) {
            case LOCAL_FACTOR -> approvals.approveLocalFactor(42L, "device", deviceProof());
            case CUSTODY_TRANSFER -> approvals.approveCustodyTransfer(42L, passkey);
            case WALLET_OUTBOUND -> approvals.approveWalletOutbound(41L, 42L, passkey, recovery, deviceProof());
            case COLD_PSBT -> approvals.approveColdWalletPsbt(42L, deviceProof());
            case DEPOSIT_CONFIRMED -> notifications.notifyDepositConfirmed(42L, transactionId, walletId, "ONCHAIN", 100, 3);
            case REQUEST_DEPOSIT_CONFIRMED -> notifications.notifyPaymentRequestDepositConfirmed(42L, transactionId,
                    UUID.randomUUID(), "public-id", walletId, "ONCHAIN", 100);
            case DEPOSIT_DETECTED -> notifications.notifyDepositDetected(42L, transactionId, walletId, "ONCHAIN", 100, 0);
            case DEPOSIT_PROGRESS -> notifications.notifyDepositConfirmationProgress(42L, transactionId, walletId, "ONCHAIN", 100, 1);
            case OUTBOUND_DETECTED -> notifications.notifyOutboundDetected(42L, transactionId, walletId, "ONCHAIN", 100, 0, "destination");
            case OUTBOUND_CONFIRMED -> notifications.notifyOutboundConfirmed(42L, transactionId, walletId, "ONCHAIN", 100, 3);
            case INTERNAL_RECEIVED -> notifications.notifyInternalTransferReceived(42L, transactionId, walletId, 100);
            case INTERNAL_SENT -> notifications.notifyInternalTransferSent(42L, transactionId, walletId, 100);
            case EXTERNAL_SENT -> notifications.notifyExternalPaymentSent(42L, transactionId, walletId, "ONCHAIN", 100);
            case PAYMENT_INITIATED -> notifications.notifyPaymentInitiated(42L, transactionId, walletId, "ONCHAIN", 100);
            case PAYMENT_BROADCAST -> notifications.notifyPaymentBroadcast(42L, transactionId, walletId, "ONCHAIN", 100, "txid");
            case PAYMENT_CONFIRMED -> notifications.notifyPaymentConfirmed(42L, transactionId, walletId, "ONCHAIN", 100, 3);
            case PAYMENT_FAILED -> notifications.notifyPaymentFailed(42L, transactionId, walletId, "ONCHAIN", 100, "code", "message");
            case RECONCILIATION_REQUIRED -> notifications.notifyPaymentReconciliationRequired(42L, transactionId, walletId, "ONCHAIN", 100, "reason");
        }
    }

    private String url(Root root) {
        return "http://server.test" + path(root);
    }

    private String operation(Root root) {
        return (root.approval() ? "remote-approval" : "remote-notification") + path(root);
    }

    private String path(Root root) {
        String suffix = switch (root) {
            case COLD_PSBT -> "cold-wallet-psbt";
            case REQUEST_DEPOSIT_CONFIRMED -> "payment-request-deposit-confirmed";
            case INTERNAL_RECEIVED -> "internal-transfer-received";
            case INTERNAL_SENT -> "internal-transfer-sent";
            case EXTERNAL_SENT -> "external-payment-sent";
            case RECONCILIATION_REQUIRED -> "payment-reconciliation-required";
            default -> root.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        };
        return "/internal/kfe/" + (root.approval() ? "transaction-approval/" : "notifications/") + suffix;
    }

    private DeviceProof deviceProof() {
        return new DeviceProof("device", "proof", "challenge", Instant.parse("2026-07-27T12:00:00Z"));
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void finishTransaction(int status) {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clear();
        callbacks.forEach(callback -> callback.afterCompletion(status));
    }
}
