package source.kfe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.model.KfeBalanceMovementEntity;
import source.kfe.model.KfeExecutionOutboxEntity;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.repository.KfeBalanceMovementRepository;
import source.kfe.repository.KfeExecutionOutboxRepository;
import source.kfe.repository.KfeIdempotencyRepository;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.repository.KfeWalletRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class KfeExecutionTransactionHelper {

    private static final String ASSET_BTC = "BTC";

    private final KfeExecutionOutboxRepository outboxRepository;
    private final KfeTransactionRepository transactionRepository;
    private final KfeWalletRepository walletRepository;
    private final KfeIdempotencyRepository idempotencyRepository;
    private final KfeBalanceMovementRepository movementRepository;
    private final KfeBalanceService balanceService;
    private final KfeAuditLogService auditLogService;
    private final KfeStatementService statementService;
    private final KfeDashboardPublisher dashboardPublisher;
    private final KfeHashService hashService;
    private final ObjectMapper objectMapper;

    public KfeExecutionTransactionHelper(
            KfeExecutionOutboxRepository outboxRepository,
            KfeTransactionRepository transactionRepository,
            KfeWalletRepository walletRepository,
            KfeIdempotencyRepository idempotencyRepository,
            KfeBalanceMovementRepository movementRepository,
            KfeBalanceService balanceService,
            KfeAuditLogService auditLogService,
            KfeStatementService statementService,
            KfeDashboardPublisher dashboardPublisher,
            KfeHashService hashService,
            ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.movementRepository = movementRepository;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
        this.statementService = statementService;
        this.dashboardPublisher = dashboardPublisher;
        this.hashService = hashService;
        this.objectMapper = objectMapper;
    }

    public record PreparationResult(
            boolean proceed,
            String operation,
            UUID transactionId,
            Long userId,
            String sourceWalletLabel,
            UUID sourceWalletId,
            String externalReference,
            long amountSats,
            long networkFeeSats,
            String memo,
            String idempotencyKey,
            String quorumProposalHash
    ) {}

    @Transactional
    public PreparationResult prepare(UUID outboxId) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId).orElse(null);
        if (outbox == null || !"PROCESSING".equals(outbox.getStatus()) || outbox.getClaimedBy() == null || outbox.getClaimedAt() == null) {
            return new PreparationResult(false, null, null, null, null, null, null, 0, 0, null, null, null);
        }

        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(outbox.getTransactionId()).orElse(null);
        if (tx == null) {
            markOutboxFailed(outbox, "TRANSACTION_NOT_FOUND", "KFE transaction does not exist.", false);
            return new PreparationResult(false, null, null, null, null, null, null, 0, 0, null, null, null);
        }
        if (tx.getStatus() == KfeTransactionStatus.SETTLED || tx.getStatus() == KfeTransactionStatus.FAILED) {
            markOutboxDispatched(outbox, firstNonBlank(tx.getProviderReference(), tx.getBlockchainTxid(), tx.getPaymentHash()));
            return new PreparationResult(false, null, null, null, null, null, null, 0, 0, null, null, null);
        }
        if (tx.getStatus() != KfeTransactionStatus.EXECUTING
                && tx.getStatus() != KfeTransactionStatus.REQUIRES_RECONCILIATION) {
            markOutboxFailed(outbox, "INVALID_TRANSACTION_STATUS",
                    "KFE transaction is not executable in status " + tx.getStatus() + ".", false);
            return new PreparationResult(false, null, null, null, null, null, null, 0, 0, null, null, null);
        }

        String op = outbox.getOperation() != null ? outbox.getOperation().trim().toUpperCase() : "";
        if (!"ONCHAIN_OUTBOUND".equals(op) && !"LIGHTNING_OUTBOUND".equals(op)) {
            if ("ONCHAIN_INBOUND".equals(op) || "LIGHTNING_INBOUND".equals(op)) {
                markRequiresReconciliation(
                        outbox.getId(),
                        tx.getId(),
                        "INBOUND_REQUIRES_TRUSTED_MONITOR",
                        "Inbound settlement must be performed by a trusted KFE network monitor.");
            } else {
                markFinalFailure(
                        outbox.getId(),
                        tx.getId(),
                        "UNSUPPORTED_OPERATION",
                        "Unsupported KFE outbox operation " + outbox.getOperation() + ".");
            }
            return new PreparationResult(false, null, null, null, null, null, null, 0, 0, null, null, null);
        }

        KfeWalletEntity sourceWallet = walletRepository.findById(tx.getSourceWalletId())
                .orElseThrow(() -> new IllegalStateException("Source KFE wallet not found."));

        JsonNode payload = payload(outbox);
        String externalReference = text(payload, "externalReference", null);
        String memo = text(payload, "memo", null);

        return new PreparationResult(
                true,
                op,
                tx.getId(),
                tx.getUserId(),
                sourceWallet.getLabel(),
                sourceWallet.getId(),
                externalReference,
                tx.getReceiverAmountSats(),
                tx.getNetworkFeeSats(),
                memo,
                tx.getIdempotencyKey(),
                tx.getQuorumProposalHash()
        );
    }

    @Transactional
    public void settleOutbound(
            UUID outboxId,
            UUID transactionId,
            String provider,
            String providerReference,
            String blockchainTxid,
            long feeSats,
            UUID sourceWalletId,
            String providerPayload) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, providerReference)) {
            return;
        }

        tx.setProvider(provider);
        tx.setProviderReference(providerReference);
        tx.setBlockchainTxid(blockchainTxid);
        if (feeSats > 0L) {
            tx.setNetworkFeeSats(feeSats);
        }

        balanceService.settleReservedDebit(sourceWalletId, ASSET_BTC, tx.getTotalDebitSats());
        movement(tx.getId(), sourceWalletId, "SETTLE_DEBIT", tx.getTotalDebitSats(), "LOCKED", null);
        transition(tx, KfeTransactionStatus.SETTLED, "KFE_TRANSACTION_SETTLED",
                Map.of("providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, ""))));
        recordStatement(tx, sourceWalletId, providerPayload);
        updateIdempotency(tx);
        markOutboxDispatched(outbox, providerReference);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    @Transactional
    public void settleOutboundLightning(
            UUID outboxId,
            UUID transactionId,
            String provider,
            String providerReference,
            String blockchainTxid,
            String paymentHash,
            long feeSats,
            UUID sourceWalletId,
            String providerPayload) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, firstNonBlank(providerReference, paymentHash, blockchainTxid))) {
            return;
        }

        tx.setProvider(provider);
        tx.setProviderReference(providerReference);
        tx.setBlockchainTxid(blockchainTxid);
        tx.setPaymentHash(paymentHash);
        if (feeSats > 0L) {
            tx.setNetworkFeeSats(feeSats);
        }

        balanceService.settleReservedDebit(sourceWalletId, ASSET_BTC, tx.getTotalDebitSats());
        movement(tx.getId(), sourceWalletId, "SETTLE_DEBIT", tx.getTotalDebitSats(), "LOCKED", null);
        transition(tx, KfeTransactionStatus.SETTLED, "KFE_TRANSACTION_SETTLED",
                Map.of("providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, ""))));
        recordStatement(tx, sourceWalletId, providerPayload);
        updateIdempotency(tx);
        markOutboxDispatched(outbox, providerReference);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    @Transactional
    public void markUnknown(
            UUID outboxId,
            UUID transactionId,
            String providerReference,
            String providerPayload,
            String message) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, providerReference)) {
            return;
        }

        tx.setProviderReference(firstNonBlank(providerReference, tx.getProviderReference()));
        tx.setFailureCode("PROVIDER_RESULT_UNKNOWN");
        tx.setFailureMessage(trim(message, 255));
        transition(tx, KfeTransactionStatus.REQUIRES_RECONCILIATION, "KFE_TRANSACTION_REQUIRES_RECONCILIATION",
                Map.of("providerReferenceHash", hashService.sha256(firstNonBlank(providerReference, ""))));
        recordStatement(tx, tx.getSourceWalletId(), providerPayload);
        updateIdempotency(tx);

        outbox.setAttempts(outbox.getAttempts() + 1);
        outbox.setStatus("UNKNOWN");
        outbox.setProviderReference(providerReference);
        outbox.setLastError(trim(message, 1000));
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    @Transactional
    public void markRetryableFailure(
            UUID outboxId,
            UUID transactionId,
            String code,
            String message) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, null)) {
            return;
        }

        outbox.setAttempts(outbox.getAttempts() + 1);
        outbox.setStatus("FAILED_RETRYABLE");
        outbox.setLastError(trim(code + ": " + message, 1000));
        outbox.setNextAttemptAt(LocalDateTime.now().plusMinutes(Math.min(60, 1L << Math.min(outbox.getAttempts(), 5))));
        clearClaim(outbox);
        outboxRepository.save(outbox);
        audit(tx, "KFE_EXECUTION_RETRYABLE_FAILURE", tx.getStatus(), tx.getStatus(),
                Map.of("failureCode", code, "errorHash", hashService.sha256(message)));
    }

    @Transactional
    public void markFinalFailure(
            UUID outboxId,
            UUID transactionId,
            String code,
            String message) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, null)) {
            return;
        }

        if (tx.getSourceWalletId() != null && tx.getTotalDebitSats() > 0L) {
            balanceService.releaseReserved(tx.getSourceWalletId(), ASSET_BTC, tx.getTotalDebitSats());
            movement(tx.getId(), tx.getSourceWalletId(), "RELEASE_RESERVE", tx.getTotalDebitSats(), "LOCKED", "AVAILABLE");
        }
        tx.setFailureCode(trim(code, 64));
        tx.setFailureMessage(trim(message, 255));
        transition(tx, KfeTransactionStatus.FAILED, "KFE_TRANSACTION_FAILED",
                Map.of("failureCode", code, "errorHash", hashService.sha256(message)));
        recordStatement(tx, firstNonNull(tx.getSourceWalletId(), tx.getDestinationWalletId()), null);
        updateIdempotency(tx);
        markOutboxFailed(outbox, code, message, false);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    @Transactional
    public void markRequiresReconciliation(
            UUID outboxId,
            UUID transactionId,
            String code,
            String message) {
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found: " + outboxId));
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));

        markRequiresReconciliation(outbox, tx, code, message);
    }

    private void markRequiresReconciliation(
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            String code,
            String message) {
        if (completeTerminalOutboxIfTransactionTerminal(outbox, tx, null)) {
            return;
        }

        tx.setFailureCode(trim(code, 64));
        tx.setFailureMessage(trim(message, 255));
        transition(tx, KfeTransactionStatus.REQUIRES_RECONCILIATION, "KFE_TRANSACTION_REQUIRES_RECONCILIATION",
                Map.of("reason", code));
        recordStatement(tx, firstNonNull(tx.getDestinationWalletId(), tx.getSourceWalletId()), null);
        updateIdempotency(tx);

        outbox.setAttempts(outbox.getAttempts() + 1);
        outbox.setStatus("UNKNOWN");
        outbox.setLastError(trim(code + ": " + message, 1000));
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
        dashboardPublisher.publishAfterCommit(tx.getUserId());
    }

    private void markOutboxDispatched(KfeExecutionOutboxEntity outbox, String providerReference) {
        outbox.setStatus("DISPATCHED");
        outbox.setProviderReference(providerReference);
        outbox.setDispatchedAt(LocalDateTime.now());
        outbox.setLastError(null);
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    private boolean completeTerminalOutboxIfTransactionTerminal(
            KfeExecutionOutboxEntity outbox,
            KfeTransactionEntity tx,
            String providerReference) {
        if (tx.getStatus() == KfeTransactionStatus.SETTLED) {
            markOutboxDispatched(outbox, firstNonBlank(
                    providerReference,
                    tx.getProviderReference(),
                    tx.getBlockchainTxid(),
                    tx.getPaymentHash()));
            return true;
        }
        if (tx.getStatus() == KfeTransactionStatus.FAILED) {
            markOutboxFinalFailed(outbox, tx);
            return true;
        }
        return false;
    }

    private void markOutboxFailed(
            KfeExecutionOutboxEntity outbox,
            String code,
            String message,
            boolean retryable) {
        outbox.setAttempts(outbox.getAttempts() + 1);
        outbox.setStatus(retryable ? "FAILED_RETRYABLE" : "FAILED_FINAL");
        String finalMsg = message != null && !message.isBlank() ? message : "KFE provider execution failed.";
        outbox.setLastError(trim(code + ": " + finalMsg, 1000));
        outbox.setNextAttemptAt(retryable
                ? LocalDateTime.now().plusMinutes(Math.min(60, 1L << Math.min(outbox.getAttempts(), 5)))
                : null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    private void markOutboxFinalFailed(KfeExecutionOutboxEntity outbox, KfeTransactionEntity tx) {
        outbox.setStatus("FAILED_FINAL");
        String code = firstNonBlank(tx.getFailureCode(), "TRANSACTION_FAILED");
        String message = firstNonBlank(tx.getFailureMessage(), "KFE transaction is already failed.");
        outbox.setLastError(trim(code + ": " + message, 1000));
        outbox.setNextAttemptAt(null);
        clearClaim(outbox);
        outboxRepository.save(outbox);
    }

    private void transition(
            KfeTransactionEntity tx,
            KfeTransactionStatus target,
            String eventType,
            Map<String, ?> auditPayload) {
        KfeTransactionStatus previous = tx.getStatus();
        tx.setStatus(target);
        transactionRepository.save(tx);
        audit(tx, eventType, previous, target, auditPayload);
    }

    private void audit(
            KfeTransactionEntity tx,
            String eventType,
            KfeTransactionStatus from,
            KfeTransactionStatus to,
            Map<String, ?> payload) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        redacted.put("transactionId", tx.getId().toString());
        redacted.put("idempotencyHash", hashService.sha256(tx.getIdempotencyKey()));
        if (payload != null) {
            redacted.putAll(payload);
        }
        auditLogService.record(eventType, tx.getId(), tx.getSourceWalletId(), from, to, redacted);
    }

    private void movement(
            UUID transactionId,
            UUID walletId,
            String movementType,
            long amountSats,
            String fromBucket,
            String toBucket) {
        KfeBalanceMovementEntity movement = new KfeBalanceMovementEntity();
        movement.setTransactionId(transactionId);
        movement.setWalletId(walletId);
        movement.setMovementType(movementType);
        movement.setAmountSats(amountSats);
        movement.setFromBucket(fromBucket);
        movement.setToBucket(toBucket);
        movementRepository.save(movement);
    }

    private void recordStatement(KfeTransactionEntity tx, UUID walletId, String providerPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", tx.getId().toString());
        payload.put("status", tx.getStatus().name());
        payload.put("rail", tx.getRail().name());
        payload.put("direction", tx.getDirection().name());
        payload.put("grossAmountSats", tx.getGrossAmountSats());
        payload.put("receiverAmountSats", tx.getReceiverAmountSats());
        payload.put("networkFeeSats", tx.getNetworkFeeSats());
        payload.put("keroseneFeeSats", tx.getKeroseneFeeSats());
        payload.put("totalDebitSats", tx.getTotalDebitSats());
        payload.put("provider", tx.getProvider());
        payload.put("providerReferenceHash", hashService.sha256(firstNonBlank(tx.getProviderReference(), "")));
        payload.put("blockchainTxid", tx.getBlockchainTxid());
        payload.put("paymentHash", tx.getPaymentHash());
        if (providerPayload != null && !providerPayload.isBlank()) {
            payload.put("providerPayloadHash", hashService.sha256(providerPayload));
        }
        statementService.recordUserStatement(tx.getUserId(), walletId, tx, payload);
    }

    private void updateIdempotency(KfeTransactionEntity tx) {
        idempotencyRepository.findById(new source.kfe.model.KfeIdempotencyId(tx.getUserId(), tx.getIdempotencyKey())).ifPresent(entity -> {
            entity.setStatus(tx.getStatus().name());
            idempotencyRepository.save(entity);
        });
    }

    private JsonNode payload(KfeExecutionOutboxEntity outbox) {
        if (outbox.getPayloadJson() == null || outbox.getPayloadJson().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(outbox.getPayloadJson());
        } catch (Exception exception) {
            throw new IllegalArgumentException("KFE outbox payload is not valid JSON.", exception);
        }
    }

    private void clearClaim(KfeExecutionOutboxEntity outbox) {
        outbox.setClaimedBy(null);
        outbox.setClaimedAt(null);
    }

    private String text(JsonNode payload, String field, String fallback) {
        JsonNode value = payload.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private UUID firstNonNull(UUID first, UUID second) {
        return first != null ? first : second;
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
