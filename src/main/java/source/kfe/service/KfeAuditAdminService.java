package source.kfe.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.dto.KfeAuditEventResponse;
import source.kfe.dto.KfeAuditLatestResponse;
import source.kfe.dto.KfeAuditRootResponse;
import source.kfe.model.KfeAuditLogEntity;
import source.kfe.repository.KfeAuditHashRow;
import source.kfe.repository.KfeAuditLogRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class KfeAuditAdminService {

    private static final String EMPTY_ROOT = "0".repeat(64);
    private static final int ROOT_PAGE_SIZE = 1_000;

    private final KfeAuditLogRepository repository;
    private final KfeHashService hashService;

    public KfeAuditAdminService(KfeAuditLogRepository repository, KfeHashService hashService) {
        this.repository = repository;
        this.hashService = hashService;
    }

    @Transactional(readOnly = true)
    public KfeAuditLatestResponse latest() {
        KfeAuditEventResponse latest = repository.findTopByOrderBySequenceNumberDesc()
                .map(this::toResponse)
                .orElse(null);
        return new KfeAuditLatestResponse(latest, root());
    }

    @Transactional(readOnly = true)
    public List<KfeAuditEventResponse> events(int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        return repository.findAllByOrderBySequenceNumberDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<KfeAuditEventResponse> transactionEvents(UUID transactionId) {
        return repository.findByTransactionIdOrderBySequenceNumberAsc(transactionId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public KfeAuditRootResponse root() {
        List<String> level = new ArrayList<>();
        Long fromSequence = null;
        Long toSequence = 0L;
        long eventCount = 0L;

        while (true) {
            List<KfeAuditHashRow> rows = repository.findHashRowsAfterSequence(
                    toSequence,
                    PageRequest.of(0, ROOT_PAGE_SIZE));
            if (rows.isEmpty()) {
                break;
            }
            for (KfeAuditHashRow row : rows) {
                if (fromSequence == null) {
                    fromSequence = row.getSequenceNumber();
                }
                toSequence = row.getSequenceNumber();
                eventCount++;
                level.add(row.getEventHash());
            }
        }

        if (level.isEmpty()) {
            return new KfeAuditRootResponse(EMPTY_ROOT, 0L, null, null, LocalDateTime.now());
        }

        while (level.size() > 1) {
            level = nextLevel(level);
        }
        return new KfeAuditRootResponse(
                level.getFirst(),
                eventCount,
                fromSequence,
                toSequence,
                LocalDateTime.now());
    }

    private List<String> nextLevel(List<String> level) {
        ArrayList<String> next = new ArrayList<>();
        for (int index = 0; index < level.size(); index += 2) {
            String left = level.get(index);
            String right = index + 1 < level.size() ? level.get(index + 1) : left;
            next.add(hashService.sha256(left + "|" + right));
        }
        return next;
    }

    private KfeAuditEventResponse toResponse(KfeAuditLogEntity event) {
        return new KfeAuditEventResponse(
                event.getSequenceNumber(),
                event.getId(),
                event.getTransactionId(),
                event.getWalletId(),
                event.getEventType(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getPayloadHash(),
                event.getPreviousHash(),
                event.getEventHash(),
                event.getCreatedAt());
    }
}
