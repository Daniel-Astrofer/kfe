package source.kfe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.dto.KfeDashboardResponse;
import source.kfe.dto.KfeDashboardWallet;
import source.kfe.dto.KfeStatementItem;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.model.KfeUserStatementEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.repository.KfeDashboardWalletRow;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.repository.KfeUserStatementRepository;
import source.kfe.repository.KfeWalletRepository;
import source.kfe.time.Utc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KfeDashboardService {

    private final KfeWalletRepository walletRepository;
    private final KfeUserStatementRepository statementRepository;
    private final KfeTransactionRepository transactionRepository;
    private final KfeResponseMapper responseMapper;
    private final ObjectMapper objectMapper;

    public KfeDashboardService(
            KfeWalletRepository walletRepository,
            KfeUserStatementRepository statementRepository,
            KfeTransactionRepository transactionRepository,
            KfeResponseMapper responseMapper,
            ObjectMapper objectMapper) {
        this.walletRepository = walletRepository;
        this.statementRepository = statementRepository;
        this.transactionRepository = transactionRepository;
        this.responseMapper = responseMapper;
        this.objectMapper = objectMapper;
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
        // Prefer live transactions_master (full labels, stable createdAt/order).
        List<KfeStatementItem> statement = buildLiveStatement(userId);
        if (statement.isEmpty()) {
            statement = statementRepository
                    .findTop25ByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(
                            userId, LocalDateTime.now(java.time.ZoneOffset.UTC))
                    .stream()
                    .map(this::toStatementItem)
                    .toList();
        }
        return new KfeDashboardResponse(wallets, statement, spendable, observed, spendable + observed);
    }

    /**
     * Rebuild recent activity from live ledger rows.
     * Identity = transactionId; sort = createdAt (fixed); status updates do not reorder.
     */
    private List<KfeStatementItem> buildLiveStatement(Long userId) {
        List<KfeTransactionEntity> rows = transactionRepository.findParticipantVisibleByUserId(
                userId,
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                PageRequest.of(0, 40));
        List<KfeStatementItem> out = new ArrayList<>(rows.size());
        for (KfeTransactionEntity tx : rows) {
            Map<String, Object> payload = new LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, userId));
            UUID walletId = tx.getDirection() == KfeDirection.INBOUND
                    ? tx.getDestinationWalletId()
                    : (tx.getSourceWalletId() != null ? tx.getSourceWalletId() : tx.getDestinationWalletId());
            String status = tx.getStatus() != null ? tx.getStatus().name() : null;
            Instant createdAt = Utc.toInstant(tx.getCreatedAt());
            Instant updatedAt = Utc.toInstant(tx.getUpdatedAt());
            out.add(new KfeStatementItem(
                    tx.getId(),
                    tx.getId(),
                    walletId,
                    status,
                    KfeTransactionStatus.displayStatusOf(tx.getStatus()),
                    toJson(payload),
                    createdAt,
                    updatedAt,
                    null));
        }
        return out;
    }

    private KfeStatementItem toStatementItem(KfeUserStatementEntity item) {
        JsonNode root = readJson(item.getDisplayPayloadJson());
        String status = text(root, "status");
        Instant payloadCreated = instant(root, "createdAt");
        Instant payloadUpdated = instant(root, "updatedAt");
        // Prefer ledger time from payload; fall back to row created_at (UTC wall).
        Instant createdAt = payloadCreated != null ? payloadCreated : Utc.toInstant(item.getCreatedAt());
        Instant updatedAt = payloadUpdated != null
                ? payloadUpdated
                : Utc.toInstant(item.getUpdatedAt() != null ? item.getUpdatedAt() : item.getCreatedAt());
        return new KfeStatementItem(
                item.getTransactionId(),
                item.getTransactionId(),
                item.getWalletId(),
                status,
                KfeTransactionStatus.displayStatusOf(status),
                item.getDisplayPayloadJson(),
                createdAt,
                updatedAt,
                Utc.toInstant(item.getExpiresAt()));
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode root, String field) {
        if (root == null || !root.has(field) || root.get(field).isNull()) {
            return null;
        }
        String value = root.get(field).asText(null);
        return value != null && !value.isBlank() ? value : null;
    }

    private static Instant instant(JsonNode root, String field) {
        String value = text(root, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String toJson(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (Exception exception) {
            return "{}";
        }
    }

    private KfeDashboardWallet toWallet(KfeDashboardWalletRow row) {
        KfeWalletKind kind = walletKind(row.getKind());
        if (kind == KfeWalletKind.WATCH_ONLY) {
            return new KfeDashboardWallet(
                    row.getWalletId(),
                    row.getKind(),
                    row.getStatus(),
                    row.getLabel(),
                    row.getLabel(),
                    responseMapper.walletTypeDescription(kind),
                    row.getAsset(),
                    false,
                    0L,
                    0L,
                    0L,
                    0L,
                    value(row.getObservedSats()),
                    row.getActiveAddress(),
                    Utc.toInstant(row.getCreatedAt()),
                    Utc.toInstant(row.getUpdatedAt()));
        }
        long observed = kind == KfeWalletKind.INTERNAL ? 0L : value(row.getObservedSats());
        return new KfeDashboardWallet(
                row.getWalletId(),
                row.getKind(),
                row.getStatus(),
                row.getLabel(),
                row.getLabel(),
                responseMapper.walletTypeDescription(kind),
                row.getAsset(),
                Boolean.TRUE.equals(row.getSpendable()),
                value(row.getAvailableSats()),
                value(row.getPendingSats()),
                value(row.getLockedSats()),
                value(row.getAutoHoldSats()),
                observed,
                row.getActiveAddress(),
                Utc.toInstant(row.getCreatedAt()),
                Utc.toInstant(row.getUpdatedAt()));
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
