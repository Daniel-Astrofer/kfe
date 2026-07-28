package com.kerosene.kfe.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.model.KfeWalletStatus;
import com.kerosene.kfe.repository.KfeWalletRepository;

import java.util.List;
import java.util.UUID;

/**
 * Manages system internal wallets (FUNDS, PROFIT) (ITEM 10 — profit segregation).
 *
 * <p>SYSTEM_FUNDS and SYSTEM_PROFIT are internal ledger wallets. They are created with
 * empty balances and exist as accounting entries only. No physical UTXO segregation.
 *
 * <p>PROFIT SEGREGATION:
 * <ul>
 *   <li>SUBLEDGER (default): Profit tracked as ledger entry within USERS. No physical separation.</li>
 *   <li>DEDICATED_BUCKET (future): Separate vault PROFIT bucket. Requires mesh support.</li>
 *   <li>PERIODIC_TRANSFER (future): Accrue in subledger, sweep periodically.</li>
 * </ul>
 *
 * <p>INVARIANT: Structural operations (wallet creation, quorum changes, key rotation)
 * MUST NOT debit the USERS bucket. System wallets are for accounting only — they cannot
 * receive user debits.
 *
 * <p>SYSTEM_PROFIT balance is a LIABILITY within USERS until physically segregated.
 * It must be included in solvency calculations.
 */
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
    private final String profitSegregationMode;

    public KfeSystemWalletService(
            KfeWalletRepository walletRepository,
            KfeBalanceService balanceService,
            KfeHashService hashService,
            @Value("${kfe.system.user-id:0}") Long systemUserId,
            @Value("${kfe.system.wallets.funds-label:Kerosene Fundos Globais}") String fundsLabel,
            @Value("${kfe.system.wallets.profit-label:Kerosene Lucro}") String profitLabel,
            @Value("${kfe.profit.segregation-mode:SUBLEDGER}") String profitSegregationMode) {
        this.walletRepository = walletRepository;
        this.balanceService = balanceService;
        this.hashService = hashService;
        this.systemUserId = systemUserId;
        this.fundsLabel = fundsLabel;
        this.profitLabel = profitLabel;
        this.profitSegregationMode = normalizeMode(profitSegregationMode);
    }

    /**
     * Creates the FUNDS and PROFIT system wallets if they do not exist.
     * Both are created with EMPTY balances — accounting entries only.
     *
     * <p>PROFIT SEGREGATION: In SUBLEDGER mode, these wallets exist as ledger entries
     * within the USERS bucket. No physical UTXO segregation is performed.
     */
    @Transactional
    public SystemWallets ensureSystemWallets() {
        KfeWalletEntity funds = ensureWallet(KfeWalletKind.SYSTEM_FUNDS, fundsLabel, true);
        KfeWalletEntity profit = ensureWallet(KfeWalletKind.SYSTEM_PROFIT, profitLabel, true);
        return new SystemWallets(funds.getId(), profit.getId());
    }

    /**
     * Returns the SYSTEM_PROFIT wallet ID.
     * SYSTEM_PROFIT is a ledger-based liability — until physically segregated,
     * its balance represents fees collected from users but NOT backed by a
     * separate UTXO pool.
     */
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

    /**
     * Returns the current profit segregation mode.
     */
    public String profitSegregationMode() {
        return profitSegregationMode;
    }

    /**
     * Returns true if profit is tracked as a subledger within USERS
     * (no physical UTXO segregation).
     */
    public boolean isProfitSubledger() {
        return "SUBLEDGER".equals(profitSegregationMode);
    }

    private KfeWalletEntity ensureWallet(KfeWalletKind kind, String label, boolean spendable) {
        return walletRepository.findFirstByUserIdAndKindAndStatusInOrderByCreatedAtDesc(
                        systemUserId,
                        kind,
                        ACTIVE_SYSTEM_STATUSES)
                .orElseGet(() -> createWallet(kind, label, spendable));
    }

    /**
     * Creates a system wallet with empty balance.
     * System wallets are INTERNAL — they CANNOT receive user debits.
     * They exist only for accounting attribution.
     */
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

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "SUBLEDGER";
        }
        return mode.trim().toUpperCase();
    }

    public record SystemWallets(UUID fundsWalletId, UUID profitWalletId) {
    }
}
