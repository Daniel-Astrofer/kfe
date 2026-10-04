package com.kerosene.kfe.bootstrap.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kerosene.common.vaultmesh.governance.VaultMeshDayStatus;
import com.kerosene.common.vaultmesh.settlement.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KfeVaultMeshReadinessProbeTest {
    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    private final VaultMeshSettlementPort vaultMesh = mock(VaultMeshSettlementPort.class);
    private final KfeVaultMeshReadinessProbe probe =
            new KfeVaultMeshReadinessProbe(
                    vaultMesh, 180_000, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(vaultMesh.getDayStatus()).thenReturn(VaultMeshDayStatus.upToDate("2026-09-08"));
        when(vaultMesh.getUsersDepositAddress()).thenReturn(keyset("tb1pusers", "02aa"));
        when(vaultMesh.getChannelsDepositAddress()).thenReturn(keyset("tb1pchannels", "02bb"));
    }

    @Test
    void reportsUpOnlyWhenDayAndBothSeparatedKeysetsAreAvailable() {
        probe.refresh();

        assertThat(probe.current().ready()).isTrue();
        assertThat(probe.current().status()).isEqualTo("UP");
    }

    @Test
    void reportsDownWhenOneKeysetDoesNotReachQuorum() {
        when(vaultMesh.getChannelsDepositAddress()).thenReturn(null);

        probe.refresh();

        assertThat(probe.current().ready()).isFalse();
        assertThat(probe.current().status()).isEqualTo("DOWN:CHANNELS_KEYSET_UNAVAILABLE");
    }

    @Test
    void reportsDownWhenUsersAndChannelsResolveToTheSameKey() {
        VaultMeshDepositInfo users = keyset("tb1psame", "02aa");
        when(vaultMesh.getUsersDepositAddress()).thenReturn(users);
        when(vaultMesh.getChannelsDepositAddress()).thenReturn(users);

        probe.refresh();

        assertThat(probe.current().ready()).isFalse();
        assertThat(probe.current().status()).isEqualTo("DOWN:KEYSETS_NOT_SEPARATED");
    }

    @Test
    void reportsDownWhenVaultDayIsStale() {
        when(vaultMesh.getDayStatus())
                .thenReturn(VaultMeshDayStatus.stale("2026-09-07", "2026-09-08"));

        probe.refresh();

        assertThat(probe.current().ready()).isFalse();
        assertThat(probe.current().status()).isEqualTo("DOWN:DAY_STALE");
    }

    @Test
    void springSelectsTheProductionConstructor() {
        new ApplicationContextRunner()
                .withPropertyValues("kfe.vaultmesh.enabled=true")
                .withBean(VaultMeshSettlementPort.class, () -> vaultMesh)
                .withUserConfiguration(KfeVaultMeshReadinessProbe.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(KfeVaultMeshReadinessProbe.class);
                });
    }

    private static VaultMeshDepositInfo keyset(String address, String outputKey) {
        return new VaultMeshDepositInfo(
                address,
                "rawtr(" + outputKey + ")",
                "frost-secp256k1-tr-v3",
                outputKey,
                outputKey,
                "testnet3");
    }
}
