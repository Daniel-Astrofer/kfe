package com.kerosene.kfe.rail;

import com.kerosene.common.vaultmesh.VaultMeshIntent;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VaultMeshChannelsMeshInjectGatewayTest {

    @Test
    void authorizeOpenRefusesInvalidInputs() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = new VaultMeshChannelsMeshInjectGateway(port);

        assertThat(gateway.authorizeOpen(0L, "02ab").authorized()).isFalse();
        assertThat(gateway.authorizeOpen(1L, " ").authorized()).isFalse();
    }

    @Test
    void authorizeOpenAcceptsWhenVaultReceiptAccepted() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = new VaultMeshChannelsMeshInjectGateway(port);

        when(port.submitIntent(
                        argThat(
                                (VaultMeshIntent i) ->
                                        i.bucket().equals("CHANNELS")
                                                && i.amountSats() == 12_345L
                                                && i.destination().equals("02abcd")
                                                && i.policyHash().isEmpty())))
                .thenReturn(
                        new VaultMeshReceipt(
                                "intent-123",
                                VaultMeshReceipt.Status.ACCEPTED,
                                "OK",
                                null,
                                Instant.now().toEpochMilli()));

        ChannelsMeshInjectGateway.InjectResult r = gateway.authorizeOpen(12_345L, "02ABCD");
        assertThat(r.authorized()).isTrue();
        assertThat(r.reasonCode().toUpperCase(Locale.ROOT)).contains("CHANNELS_INJECT_ACCEPTED");
    }

    @Test
    void authorizeOpenRefusesWhenVaultReceiptRejected() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = new VaultMeshChannelsMeshInjectGateway(port);

        when(port.submitIntent(argThat(i -> i.bucket().equals("CHANNELS"))))
                .thenReturn(
                        new VaultMeshReceipt(
                                "intent-123",
                                VaultMeshReceipt.Status.REJECTED,
                                "NO_CAPITAL",
                                null,
                                Instant.now().toEpochMilli()));

        ChannelsMeshInjectGateway.InjectResult r = gateway.authorizeOpen(1_000L, "02abcd");
        assertThat(r.authorized()).isFalse();
        assertThat(r.reasonCode().toUpperCase(Locale.ROOT)).contains("CHANNELS_INJECT_REJECTED");
    }
}

