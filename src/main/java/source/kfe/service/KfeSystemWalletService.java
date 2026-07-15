package source.kfe.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.repository.KfeWalletRepository;

import java.util.List;
import java.util.UUID;

@Service
public class KfeSystemWalletService {

    public static final String ASSET_BTC = "BTC";

    private static final List<KfeWalletStatus> ACTIVE_SYSTEM_STATUSES = List.of(
            KfeWalletStatus.CREATING,
            KfeWalletStatus.ACTIVE,
            KfeWalletStatus.FROZEN,
            KfeWalletStatus.ROTATING_ADDRESS);

    private final KfeWalletRepository walletRepository;
    private final KfeBalanceService balanceService;
    private final KfeHashService hashService;
    private final Long systemUserId;
    private final String fundsLabel;
    private final String profitLabel;

    public KfeSystemWalletService(
            KfeWalletRepository walletRepository,
            KfeBalanceService balanceService,
            KfeHashService hashService,
            @Value("${kfe.system.user-id:0}") Long systemUserId,
            @Value("${kfe.system.wallets.funds-label:Kerosene Fundos Globais}") String fundsLabel,
            @Value("${kfe.system.wallets.profit-label:Kerosene Lucro}") String profitLabel) {
        this.walletRepository = walletRepository;
        this.balanceService = balanceService;
        this.hashService = hashService;
        this.systemUserId = systemUserId;
        this.fundsLabel = fundsLabel;
        this.profitLabel = profitLabel;
    }

    @Transactional
    public SystemWallets ensureSystemWallets() {
        KfeWalletEntity funds = ensureWallet(KfeWalletKind.SYSTEM_FUNDS, fundsLabel, true);
        KfeWalletEntity profit = ensureWallet(KfeWalletKind.SYSTEM_PROFIT, profitLabel, true);
        return new SystemWallets(funds.getId(), profit.getId());
    }

    @Transactional(readOnly = true)
    public UUID requireProfitWalletId() {
        return walletRepository.findFirstByUserIdAndKindAndStatusInOrderByCreatedAtDesc(
                        systemUserId,
                        KfeWalletKind.SYSTEM_PROFIT,
                        ACTIVE_SYSTEM_STATUSES)
                .map(KfeWalletEntity::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "KFE system profit wallet is not initialized."));
    }

    public Long systemUserId() {
        return systemUserId;
    }

    private KfeWalletEntity ensureWallet(KfeWalletKind kind, String label, boolean spendable) {
        return walletRepository.findFirstByUserIdAndKindAndStatusInOrderByCreatedAtDesc(
                        systemUserId,
                        kind,
                        ACTIVE_SYSTEM_STATUSES)
                .orElseGet(() -> createWallet(kind, label, spendable));
    }

    private KfeWalletEntity createWallet(KfeWalletKind kind, String label, boolean spendable) {
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setUserId(systemUserId);
        wallet.setKind(kind);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet.setLabel(label);
        wallet.setAsset(ASSET_BTC);
        wallet.setSpendable(spendable);
        wallet.setQuorumPolicyHash(hashService.sha256("KFE_SYSTEM_WALLET_POLICY|kind=" + kind));
        wallet = walletRepository.save(wallet);
        balanceService.createEmptyBalance(wallet.getId(), wallet.getAsset());
        return wallet;
    }

    public record SystemWallets(UUID fundsWalletId, UUID profitWalletId) {
    }
}
