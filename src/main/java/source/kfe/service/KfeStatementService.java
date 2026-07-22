package source.kfe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
 *
 * <p>Uses PostgreSQL {@code ON CONFLICT} for concurrent writers. Joins the <b>caller
 * transaction</b> ({@link Propagation#REQUIRED}) so a statement written during submit
 * can see the still-uncommitted {@code transactions_master} row (FK). {@code REQUIRES_NEW}
 * was tried and rejected: it cannot see the parent insert and blew up submit with
 * {@code UnexpectedRollbackException} after device-key approval already returned 200.
 */
@Service
public class KfeStatementService {

    private static final Logger log = LoggerFactory.getLogger(KfeStatementService.class);

    private final KfeUserStatementRepository statementRepository;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final TransactionEventPublisher transactionEventPublisher;
    private final KfeStatementService self;

    public KfeStatementService(
            KfeUserStatementRepository statementRepository,
            ObjectMapper objectMapper,
            EntityManager entityManager,
            ObjectProvider<TransactionEventPublisher> transactionEventPublisher,
            @Lazy KfeStatementService self) {
        this.statementRepository = statementRepository;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.transactionEventPublisher = transactionEventPublisher.getIfAvailable();
        this.self = self != null ? self : this;
    }

    /**
     * Upsert statement for the user/tx pair. Safe on every lifecycle stage.
     * Must not throw into the financial path when possible — callers also wrap.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordUserStatement(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        if (userId == null || transaction == null || transaction.getId() == null) {
            return;
        }
        // Flush parent tx row so native INSERT FK sees it in this connection.
        if (entityManager != null) {
            try {
                entityManager.flush();
            } catch (RuntimeException ignored) {
                // Entity may already be clean; native path still tries.
            }
        }
        try {
            if (entityManager != null) {
                nativeUpsert(userId, walletId, transaction, payload);
            } else {
                jpaUpsert(userId, walletId, transaction, payload);
            }
            publishTransactionEvent(userId, payload);
        } catch (RuntimeException first) {
            // Unique race only: re-load and update. Do NOT swallow FK / other errors that
            // leave the JDBC connection aborted — rethrow so caller can fail cleanly
            // rather than UnexpectedRollbackException later.
            if (!isUniqueViolation(first)) {
                log.error(
                        "KFE statement upsert failed userId={} txId={}: {}",
                        userId,
                        transaction.getId(),
                        first.getMessage());
                throw first;
            }
            log.debug(
                    "KFE statement unique race userId={} txId={} — JPA update",
                    userId,
                    transaction.getId());
            try {
                jpaUpsert(userId, walletId, transaction, payload);
                publishTransactionEvent(userId, payload);
            } catch (DataIntegrityViolationException race) {
                jpaUpsert(userId, walletId, transaction, payload);
                publishTransactionEvent(userId, payload);
            }
        }
    }

    private void publishTransactionEvent(Long userId, Map<String, ?> payload) {
        if (transactionEventPublisher == null || payload == null) {
            return;
        }
        transactionEventPublisher.publishAfterCommit(userId, payload);
    }

    /**
     * Best-effort statement write that never poisons the caller transaction.
     * Used from outbox/monitor paths where display cache is secondary.
     */
    public void recordUserStatementBestEffort(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        try {
            self.recordUserStatement(userId, walletId, transaction, payload);
        } catch (RuntimeException exception) {
            log.error(
                    "KFE statement best-effort abandoned userId={} txId={}: {}",
                    userId,
                    transaction != null ? transaction.getId() : null,
                    exception.getMessage());
        }
    }

    @Transactional
    public void recordUserStatementIfAbsent(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        if (userId == null || transaction == null || transaction.getId() == null) {
            return;
        }
        if (statementRepository.existsByUserIdAndTransactionId(userId, transaction.getId())) {
            return;
        }
        self.recordUserStatement(userId, walletId, transaction, payload);
    }

    @Transactional
    public void refreshTransactionDisplayPayload(KfeTransactionEntity transaction, Map<String, ?> payload) {
        if (transaction == null || transaction.getId() == null || payload == null) {
            return;
        }
        Long userId = transaction.getUserId();
        if (userId == null) {
            return;
        }
        self.recordUserStatement(userId, null, transaction, payload);
    }

    private void nativeUpsert(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        UUID transactionId = transaction.getId();
        String json = toJson(payload);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiresAt = now.plusHours(24);
        LocalDateTime orderAt = transaction.getCreatedAt() != null ? transaction.getCreatedAt() : now;
        UUID rowId = UUID.randomUUID();

        entityManager.createNativeQuery("""
                INSERT INTO financial.user_statement_24h
                    (id, user_id, transaction_id, wallet_id, display_payload_json, expires_at, created_at, updated_at)
                VALUES
                    (CAST(:id AS uuid), :userId, CAST(:transactionId AS uuid), CAST(:walletId AS uuid),
                     CAST(:payload AS text), CAST(:expiresAt AS timestamp), CAST(:createdAt AS timestamp),
                     CAST(:updatedAt AS timestamp))
                ON CONFLICT (user_id, transaction_id) DO UPDATE SET
                    wallet_id = COALESCE(EXCLUDED.wallet_id, financial.user_statement_24h.wallet_id),
                    display_payload_json = EXCLUDED.display_payload_json,
                    expires_at = EXCLUDED.expires_at,
                    updated_at = EXCLUDED.updated_at
                """)
                .setParameter("id", rowId.toString())
                .setParameter("userId", userId)
                .setParameter("transactionId", transactionId.toString())
                .setParameter("walletId", walletId != null ? walletId.toString() : null)
                .setParameter("payload", json)
                .setParameter("expiresAt", expiresAt)
                .setParameter("createdAt", orderAt)
                .setParameter("updatedAt", now)
                .executeUpdate();
    }

    private void jpaUpsert(
            Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        UUID transactionId = transaction.getId();
        String json = toJson(payload);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
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
        statement.markNew();
        statement.setUserId(userId);
        statement.setWalletId(walletId);
        statement.setTransactionId(transactionId);
        statement.setDisplayPayloadJson(json);
        statement.setExpiresAt(expiresAt);
        LocalDateTime orderAt = transaction.getCreatedAt() != null
                ? transaction.getCreatedAt()
                : now;
        statement.setCreatedAt(orderAt);
        statement.setUpdatedAt(now);
        statementRepository.save(statement);
    }

    private static boolean isUniqueViolation(Throwable exception) {
        Throwable cursor = exception;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null
                    && (message.contains("uq_user_statement_24h_user_tx")
                            || message.contains("duplicate key")
                            || message.contains("Unique index")
                            || message.contains("23505"))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return exception instanceof DataIntegrityViolationException
                && exception.getMessage() != null
                && exception.getMessage().contains("duplicate");
    }

    private String toJson(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (Exception exception) {
            return "{}";
        }
    }
}
