package com.kerosene.kfe.rail;

import com.kerosene.common.vaultmesh.VaultMeshIntent;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VaultMeshChannelsMeshInjectGatewayTest {

    @Test
    void authorizeOpenIsNonMutatingReadinessCheck() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = new VaultMeshChannelsMeshInjectGateway(port);

        assertThat(gateway.authorizeOpen(0L, "02ab").authorized()).isFalse();
        assertThat(gateway.authorizeOpen(1L, " ").authorized()).isFalse();
        assertThat(gateway.authorizeOpen(1_000L, "02abcd").authorized()).isTrue();
        assertThat(gateway.authorizeOpen(1_000L, "02abcd").reasonCode())
                .contains("CHANNELS_INJECT_READY");
        verify(port, never()).reserveIntent(any());
        verify(port, never()).submitIntent(any());
    }

    @Test
    void reserveOpenAcceptsWhenVaultReceiptAccepted() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = new VaultMeshChannelsMeshInjectGateway(port);

        when(port.reserveIntent(
                        argThat(
                                (VaultMeshIntent i) ->
                                        i.bucket().equals("CHANNELS")
                                                && i.amountSats() == 12_345L
                                                && i.destination()
                                                        .equals(
                                                                VaultMeshChannelsMeshInjectGateway
                                                                        .CHANNELS_DESTINATION)
                                                && i.intentId().equals("channels-inject-open-1")
                                                && i.policyHash().isEmpty())))
                .thenReturn(
                        new VaultMeshReceipt(
                                "channels-inject-open-1",
                                VaultMeshReceipt.Status.ACCEPTED,
                                "RESERVED",
                                null,
                                Instant.now().toEpochMilli()));

        ChannelsMeshInjectGateway.DebitResult r =
                gateway.reserveOpen("channels-inject-open-1", 12_345L, "02ABCD");
        assertThat(r.authorized()).isTrue();
        assertThat(r.intentId()).isEqualTo("channels-inject-open-1");
        assertThat(r.reasonCode().toUpperCase(Locale.ROOT)).contains("CHANNELS_INJECT_RESERVED");
    }

    @Test
    void reserveOpenRefusesWhenVaultReceiptRejected() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = new VaultMeshChannelsMeshInjectGateway(port);

        when(port.reserveIntent(argThat(i -> i.bucket().equals("CHANNELS"))))
                .thenReturn(
                        new VaultMeshReceipt(
                                "intent-123",
                                VaultMeshReceipt.Status.REJECTED,
                                "NO_CAPITAL",
                                null,
                                Instant.now().toEpochMilli()));

        ChannelsMeshInjectGateway.DebitResult r =
                gateway.reserveOpen("intent-123", 1_000L, "02abcd");
        assertThat(r.authorized()).isFalse();
        assertThat(r.reasonCode().toUpperCase(Locale.ROOT)).contains("CHANNELS_INJECT_RESERVE_REJECTED");
    }

    @Test
    void releaseAndCommitDelegateToSettlementPort() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = new VaultMeshChannelsMeshInjectGateway(port);

        when(port.releaseIntent(eq("intent-1"), eq("CHANNELS"), eq(500L)))
                .thenReturn(
                        new VaultMeshReceipt(
                                "intent-1",
                                VaultMeshReceipt.Status.ACCEPTED,
                                "RELEASED",
                                null,
                                Instant.now().toEpochMilli()));
        when(port.commitIntent(eq("intent-1")))
                .thenReturn(
                        new VaultMeshReceipt(
                                "intent-1",
                                VaultMeshReceipt.Status.ACCEPTED,
                                "COMMITTED",
                                null,
                                Instant.now().toEpochMilli()));

        assertThat(gateway.releaseOpen("intent-1", 500L, "02ab").authorized()).isTrue();
        assertThat(gateway.commitOpen("intent-1").authorized()).isTrue();
        verify(port).releaseIntent("intent-1", "CHANNELS", 500L);
        verify(port).commitIntent("intent-1");
        verify(port, never()).submitIntent(any());
    }
}
