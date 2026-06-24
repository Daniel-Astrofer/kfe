package source.kfe.dto;

import java.util.List;

public record KfeDashboardResponse(
        List<KfeDashboardWallet> wallets,
        List<KfeStatementItem> recentStatement,
        long totalSpendableSats,
        long totalObservedSats,
        long totalVisibleSats) {
}
