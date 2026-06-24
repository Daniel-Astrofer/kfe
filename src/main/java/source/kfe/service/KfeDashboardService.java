package source.kfe.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.dto.KfeDashboardResponse;
import source.kfe.dto.KfeDashboardWallet;
import source.kfe.dto.KfeStatementItem;
import source.kfe.model.KfeWalletKind;
import source.kfe.repository.KfeDashboardWalletRow;
import source.kfe.repository.KfeUserStatementRepository;
import source.kfe.repository.KfeWalletRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class KfeDashboardService {

    private final KfeWalletRepository walletRepository;
    private final KfeUserStatementRepository statementRepository;
    private final KfeResponseMapper responseMapper;

    public KfeDashboardService(
            KfeWalletRepository walletRepository,
            KfeUserStatementRepository statementRepository,
            KfeResponseMapper responseMapper) {
        this.walletRepository = walletRepository;
        this.statementRepository = statementRepository;
        this.responseMapper = responseMapper;
    }

    @Transactional(readOnly = true)
    public KfeDashboardResponse dashboard(Long userId) {
        List<KfeDashboardWallet> wallets = walletRepository.findDashboardRows(userId).stream()
                .map(this::toWallet)
                .toList();
        long spendable = wallets.stream()
                .filter(KfeDashboardWallet::spendable)
                .mapToLong(wallet -> wallet.availableSats() + wallet.pendingSats() + wallet.lockedSats())
                .sum();
        long observed = wallets.stream()
                .filter(wallet -> !wallet.spendable())
                .mapToLong(KfeDashboardWallet::observedSats)
                .sum();
        List<KfeStatementItem> statement = statementRepository
                .findTop25ByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(userId, LocalDateTime.now())
                .stream()
                .map(item -> new KfeStatementItem(
                        item.getId(),
                        item.getTransactionId(),
                        item.getWalletId(),
                        item.getDisplayPayloadJson(),
                        item.getCreatedAt(),
                        item.getExpiresAt()))
                .toList();
        return new KfeDashboardResponse(wallets, statement, spendable, observed, spendable + observed);
    }

    private KfeDashboardWallet toWallet(KfeDashboardWalletRow row) {
        return new KfeDashboardWallet(
                row.getWalletId(),
                row.getKind(),
                row.getStatus(),
                row.getLabel(),
                row.getLabel(),
                responseMapper.walletTypeDescription(walletKind(row.getKind())),
                row.getAsset(),
                Boolean.TRUE.equals(row.getSpendable()),
                value(row.getAvailableSats()),
                value(row.getPendingSats()),
                value(row.getLockedSats()),
                value(row.getAutoHoldSats()),
                value(row.getObservedSats()),
                row.getActiveAddress(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private long value(Long value) {
        return value != null ? value : 0L;
    }

    private KfeWalletKind walletKind(String value) {
        if (value == null || value.isBlank()) {
            return KfeWalletKind.INTERNAL;
        }
        try {
            return KfeWalletKind.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return KfeWalletKind.INTERNAL;
        }
    }
}
