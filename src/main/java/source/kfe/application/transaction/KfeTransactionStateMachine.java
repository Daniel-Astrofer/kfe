package source.kfe.application.transaction;

import org.springframework.stereotype.Service;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.service.KfeAuditLogService;
import source.kfe.service.KfeHashService;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class KfeTransactionStateMachine {

    private final KfeTransactionRepository transactionRepository;
    private final KfeAuditLogService auditLogService;
    private final KfeHashService hashService;

    public KfeTransactionStateMachine(
            KfeTransactionRepository transactionRepository,
            KfeAuditLogService auditLogService,
            KfeHashService hashService) {
        this.transactionRepository = transactionRepository;
        this.auditLogService = auditLogService;
        this.hashService = hashService;
    }

    public void transition(
            KfeTransactionEntity tx,
            KfeTransactionStatus target,
            String eventType,
            Map<String, ?> auditPayload) {
        KfeTransactionStatus previous = tx.getStatus();
        if (!canTransition(previous, target)) {
            throw new IllegalStateException("Invalid KFE transaction transition from " + previous + " to " + target + ".");
        }
        tx.setStatus(target);
        transactionRepository.save(tx);
        audit(tx, eventType, previous, target, auditPayload);
    }

    public void audit(
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

    private boolean canTransition(KfeTransactionStatus current, KfeTransactionStatus target) {
        if (current == target) {
            return true;
        }
        if (current == KfeTransactionStatus.SETTLED || current == KfeTransactionStatus.FAILED) {
            return false;
        }
        return switch (current) {
            case INTENT -> target == KfeTransactionStatus.VALIDATING || target == KfeTransactionStatus.FAILED;
            case VALIDATING -> target == KfeTransactionStatus.QUORUM_SYNC || target == KfeTransactionStatus.FAILED;
            case QUORUM_SYNC -> target == KfeTransactionStatus.LOCKED
                    || target == KfeTransactionStatus.FAILED
                    || target == KfeTransactionStatus.REQUIRES_RECONCILIATION;
            case LOCKED -> target == KfeTransactionStatus.EXECUTING
                    || target == KfeTransactionStatus.SETTLED
                    || target == KfeTransactionStatus.FAILED
                    || target == KfeTransactionStatus.REQUIRES_RECONCILIATION;
            case EXECUTING -> target == KfeTransactionStatus.SETTLED
                    || target == KfeTransactionStatus.FAILED
                    || target == KfeTransactionStatus.REQUIRES_RECONCILIATION;
            case REQUIRES_RECONCILIATION -> target == KfeTransactionStatus.EXECUTING
                    || target == KfeTransactionStatus.SETTLED
                    || target == KfeTransactionStatus.FAILED;
            default -> false;
        };
    }
}
