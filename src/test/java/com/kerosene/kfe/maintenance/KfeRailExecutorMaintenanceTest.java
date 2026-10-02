package com.kerosene.kfe.maintenance;

import com.kerosene.kfe.rail.CustodyGateway;
import com.kerosene.kfe.rail.KfeOnchainPaymentGateway;
import com.kerosene.kfe.rail.LightningPaymentGateway;
import com.kerosene.kfe.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeRailExecutorMaintenanceTest {
    private enum Rail { ONCHAIN, LIGHTNING }
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final KfeExecutionTransactionHelper helper = mock(KfeExecutionTransactionHelper.class);
    private final KfePreparedExecutionService prepared = mock(KfePreparedExecutionService.class);
    private final KfeOnchainPaymentGateway onchain = mock(KfeOnchainPaymentGateway.class);
    private final LightningPaymentGateway lightning = mock(LightningPaymentGateway.class);
    private final UUID outboxId = UUID.randomUUID(), txId = UUID.randomUUID(), walletId = UUID.randomUUID(), token = UUID.randomUUID();
    private KfeOnchainOutboundExecutor btc;
    private KfeLightningOutboundExecutor ln;

    @BeforeEach void setup() {
        btc = new KfeOnchainOutboundExecutor(helper, onchain, prepared);
        ln = new KfeLightningOutboundExecutor(helper, lightning, prepared);
        btc.setMaintenanceGuard(guard); ln.setMaintenanceGuard(guard);
        when(store.admit(anyString())).thenReturn(admission);
    }

    @ParameterizedTest @EnumSource(Rail.class)
    void drainRejectsDirectExecutorEvenWithPreparedClaimContextBeforeAnyEffects(Rail rail) {
        drain();
        assertThatThrownBy(() -> execute(rail)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects(); verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest @EnumSource(Rail.class)
    void unavailableInjectionRejectsDirectExecutor(Rail rail) {
        btc.setMaintenanceGuard(KfeMaintenanceGuard.unavailable()); ln.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
        assertThatThrownBy(() -> execute(rail)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects(); verifyNoInteractions(store);
    }

    @ParameterizedTest @EnumSource(Rail.class)
    void admissionOutageCannotReachProviderOrDecryptPreparedPayload(Rail rail) {
        when(store.admit(anyString())).thenThrow(new IllegalStateException("synthetic storage outage"));
        assertThatThrownBy(() -> execute(rail)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects();
    }

    @ParameterizedTest @EnumSource(Rail.class)
    void supportsIsPureAndRemainsAvailableWithoutInjection(Rail rail) {
        btc.setMaintenanceGuard(KfeMaintenanceGuard.unavailable()); ln.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
        assertThat(target(rail).supports(rail.name() + "_OUTBOUND")).isTrue();
        assertThat(target(rail).supports("UNKNOWN")).isFalse();
        verifyNoInteractions(store); noEffects();
    }

    @ParameterizedTest @EnumSource(Rail.class)
    void alreadyAdmittedParentEnclosesProviderThroughRecordingDuringDrain(Rail rail) {
        prepare(rail);
        guard.executeMutation("synthetic.parent", () -> { drain(); execute(rail); return null; });
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        if (rail == Rail.ONCHAIN) verify(onchain).broadcastPrepared(any());
        else verify(lightning).payPreparedLightning(any());
        assertThatThrownBy(() -> execute(rail)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
    }

    @ParameterizedTest @EnumSource(Rail.class)
    void successfulProviderReplyIsNotFinancialOrRecoveryCompletionEvidence(Rail rail) {
        prepare(rail); execute(rail);
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
        if (rail == Rail.ONCHAIN) verify(helper).recordOutboundBroadcast(eq(outboxId), eq(txId), eq(token),
                eq("synthetic-core"), eq("synthetic-txid"), eq("synthetic-txid"), eq(25L), eq(walletId), eq("{}"));
        else verify(helper).settleOutboundLightning(eq(outboxId), eq(txId), eq(token), eq("synthetic-lnd"),
                eq("synthetic-provider-ref"), isNull(), eq("synthetic-payment-hash"), eq(25L), eq(walletId), eq("{}"));
    }

    private void prepare(Rail rail) {
        if (rail == Rail.ONCHAIN) {
            var payload = new KfeOnchainPaymentGateway.PreparedOnchainPayment("synthetic-raw", "synthetic-txid", 25,
                    "funded", "combined", "hash", List.of(), "synthetic-intent", "{}");
            when(prepared.load(outboxId, txId, token, "ONCHAIN_OUTBOUND", KfePreparedExecutionService.PayloadType.ONCHAIN,
                    KfeOnchainPaymentGateway.PreparedOnchainPayment.class))
                    .thenReturn(Optional.of(new KfePreparedExecutionService.StoredPayload<>(payload, "synthetic-txid")));
            when(onchain.broadcastPrepared(payload)).thenReturn(new KfeOnchainPaymentGateway.PaymentResult(
                    "synthetic-core", "synthetic-txid", null, "SUCCESS", 25, "{}"));
            when(onchain.providerName()).thenReturn("synthetic-core");
        } else {
            when(lightning.isLive()).thenReturn(true);
            var payload = mock(LightningPaymentGateway.PreparedLightningPayment.class);
            when(payload.paymentHash()).thenReturn("synthetic-payment-hash");
            when(prepared.load(outboxId, txId, token, "LIGHTNING_OUTBOUND", KfePreparedExecutionService.PayloadType.LIGHTNING,
                    LightningPaymentGateway.PreparedLightningPayment.class))
                    .thenReturn(Optional.of(new KfePreparedExecutionService.StoredPayload<>(payload, "synthetic-payment-hash")));
            when(lightning.payPreparedLightning(payload)).thenReturn(new CustodyGateway.PaymentResult(
                    "synthetic-provider-ref", null, "synthetic-payment-hash", "SUCCESS", 25, "{}"));
            when(lightning.providerName()).thenReturn("synthetic-lnd");
        }
    }
    private KfeRailExecution target(Rail rail) { return rail == Rail.ONCHAIN ? btc : ln; }
    private void execute(Rail rail) {
        target(rail).execute(outboxId, new KfeExecutionTransactionHelper.PreparationResult(true, rail.name() + "_OUTBOUND",
                txId, 7L, "synthetic-wallet", walletId, "synthetic-destination", 50_000, 25, "synthetic-memo",
                "synthetic-idempotency", "synthetic-proof", 1L, 6, token));
    }
    private void drain() { when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain")); }
    private void noEffects() { verifyNoInteractions(prepared, helper, onchain, lightning); }
}
