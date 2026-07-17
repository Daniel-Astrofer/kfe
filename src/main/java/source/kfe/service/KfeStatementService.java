package source.kfe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeUserStatementEntity;
import source.kfe.repository.KfeUserStatementRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * 24h operational statement buffer with <b>one row per (user, transaction)</b>.
 *
 * <p>Cohesion rules:
 * <ul>
 *   <li>Upsert on every status/conf update — never insert a second row for the same tx.
 *   <li>{@code created_at} is fixed (ledger time) so order never jumps.
 *   <li>{@code updated_at} + payload change so the client merges in place.
 * </ul>
 */
@Service
public class KfeStatementService {

    private static final Logger log = LoggerFactory.getLogger(KfeStatementService.class);

    private final KfeUserStatementRepository statementRepository;
    private final ObjectMapper objectMapper;

    public KfeStatementService(KfeUserStatementRepository statementRepository, ObjectMapper objectMapper) {
        this.statementRepository = statementRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Upsert statement for the user/tx pair. Safe to call on every lifecycle stage
     * (INTENT → EXECUTING → SETTLED / FAILED).
     */
    @Transactional
    public void recordUserStatement(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        if (userId == null || transaction == null || transaction.getId() == null) {
            return;
        }
        try {
            upsert(userId, walletId, transaction, payload);
        } catch (DataIntegrityViolationException race) {
            // Concurrent first-insert: loser retries as update.
            log.debug(
                    "KFE statement race userId={} txId={} — retry upsert",
                    userId,
                    transaction.getId());
            upsert(userId, walletId, transaction, payload);
        }
    }

    /**
     * Insert only when missing. Prefer {@link #recordUserStatement} so status updates refresh
     * the same row; kept for call sites that intentionally avoid overwriting.
     */
    @Transactional
    public void recordUserStatementIfAbsent(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        if (userId == null || transaction == null || transaction.getId() == null) {
            return;
        }
        if (statementRepository.existsByUserIdAndTransactionId(userId, transaction.getId())) {
            return;
        }
        recordUserStatement(userId, walletId, transaction, payload);
    }

    /**
     * Refresh frozen display payload (confirmations/status) on the existing row without changing
     * {@code created_at} / sort order.
     */
    @Transactional
    public void refreshTransactionDisplayPayload(KfeTransactionEntity transaction, Map<String, ?> payload) {
        if (transaction == null || transaction.getId() == null || payload == null) {
            return;
        }
        Long userId = transaction.getUserId();
        if (userId == null) {
            return;
        }
        // Upsert so first conf refresh still creates the row if missing.
        recordUserStatement(userId, null, transaction, payload);
    }

    private void upsert(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        UUID transactionId = transaction.getId();
        String json = toJson(payload);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        LocalDateTime expiresAt = now.plusHours(24);

        var existing = statementRepository.findByUserIdAndTransactionId(userId, transactionId);
        if (existing.isPresent()) {
            KfeUserStatementEntity statement = existing.get();
            if (walletId != null) {
                statement.setWalletId(walletId);
            }
            statement.setDisplayPayloadJson(json);
            statement.setExpiresAt(expiresAt);
            statement.setUpdatedAt(now);
            statementRepository.save(statement);
            return;
        }

        KfeUserStatementEntity statement = new KfeUserStatementEntity();
        statement.setUserId(userId);
        statement.setWalletId(walletId);
        statement.setTransactionId(transactionId);
        statement.setDisplayPayloadJson(json);
        statement.setExpiresAt(expiresAt);
        // Order key = ledger created_at so late statement write does not reorder history.
        LocalDateTime orderAt = transaction.getCreatedAt() != null
                ? transaction.getCreatedAt()
                : now;
        statement.setCreatedAt(orderAt);
        statement.setUpdatedAt(now);
        statementRepository.save(statement);
    }

    private String toJson(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (Exception exception) {
            return "{}";
        }
    }
}
