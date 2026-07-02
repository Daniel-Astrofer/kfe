package source.kfe.service;

import org.junit.jupiter.api.Test;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.repository.KfeWalletRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeSystemWalletServiceTest {

    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeHashService hashService = mock(KfeHashService.class);
    private final KfeSystemWalletService service = new KfeSystemWalletService(
            walletRepository,
            balanceService,
            hashService,
            0L,
            "Fundos",
            "Lucro");

    @Test
    void createsMissingSystemWalletsWithEmptyBalances() {
        when(walletRepository.findFirstByUserIdAndKindAndStatusInOrderByCreatedAtDesc(
                any(), any(), anyCollection())).thenReturn(Optional.empty());
        when(hashService.sha256(any())).thenReturn("policy-hash");
        when(walletRepository.save(any(KfeWalletEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        KfeSystemWalletService.SystemWallets wallets = service.ensureSystemWallets();

        assertThat(wallets.fundsWalletId()).isNotNull();
        assertThat(wallets.profitWalletId()).isNotNull();
        verify(balanceService).createEmptyBalance(wallets.fundsWalletId(), "BTC");
        verify(balanceService).createEmptyBalance(wallets.profitWalletId(), "BTC");
    }

    @Test
    void returnsExistingProfitWalletId() {
        KfeWalletEntity wallet = new KfeWalletEntity();
        UUID walletId = UUID.randomUUID();
        wallet.setId(walletId);
        wallet.setUserId(0L);
        wallet.setKind(KfeWalletKind.SYSTEM_PROFIT);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        when(walletRepository.findFirstByUserIdAndKindAndStatusInOrderByCreatedAtDesc(
                any(), any(), anyCollection())).thenReturn(Optional.of(wallet));

        assertThat(service.requireProfitWalletId()).isEqualTo(walletId);
    }
}
