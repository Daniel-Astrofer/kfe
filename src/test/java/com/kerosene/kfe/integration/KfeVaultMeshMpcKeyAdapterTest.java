package com.kerosene.kfe.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.common.financial.FinancialMpcKeyPort;
import com.kerosene.common.vaultmesh.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class KfeVaultMeshMpcKeyAdapterTest {

    private static final UUID WALLET_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");

    @Test
    void keygenWalletReturnsXonlyPubkeyWhenPresent() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        when(port.getUsersDepositAddress()).thenReturn(new VaultMeshDepositInfo(
                "tb1paddress",
                "tr(...)#descriptor",
                "taproot",
                "0123456789abcdef",
                "fedcba9876543210",
                "testnet"));
        ObjectProvider<VaultMeshSettlementPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);

        FinancialMpcKeyPort adapter = new KfeVaultMeshMpcKeyAdapter(provider);

        assertThat(adapter.keygenWallet(WALLET_ID, 42L)).isEqualTo("fedcba9876543210");
    }

    @Test
    void keygenWalletFallsBackToOutputPubkeyThenAddress() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        when(port.getUsersDepositAddress()).thenReturn(new VaultMeshDepositInfo(
                "tb1pfallback",
                null,
                "taproot",
                "outputpub",
                null,
                "testnet"));
        ObjectProvider<VaultMeshSettlementPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);

        FinancialMpcKeyPort adapter = new KfeVaultMeshMpcKeyAdapter(provider);

        assertThat(adapter.keygenWallet(WALLET_ID, 42L)).isEqualTo("outputpub");
    }

    @Test
    void keygenWalletFailsClosedWhenMeshDepositUnavailable() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        when(port.getUsersDepositAddress()).thenReturn(null);
        ObjectProvider<VaultMeshSettlementPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);

        FinancialMpcKeyPort adapter = new KfeVaultMeshMpcKeyAdapter(provider);

        assertThatThrownBy(() -> adapter.keygenWallet(WALLET_ID, 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USERS deposit key unavailable");
    }

    @Test
    void keygenWalletFailsClosedWhenSettlementPortMissing() {
        ObjectProvider<VaultMeshSettlementPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        FinancialMpcKeyPort adapter = new KfeVaultMeshMpcKeyAdapter(provider);

        assertThatThrownBy(() -> adapter.keygenWallet(WALLET_ID, 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("settlement port unavailable");
    }
}
