package source.kfe.application.transaction;

import org.springframework.stereotype.Service;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.service.KfeResponseMapper;
import source.kfe.service.KfeStatementService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class KfeTransactionStatementRecorder {

    private final KfeStatementService statementService;
    private final KfeResponseMapper responseMapper;

    public KfeTransactionStatementRecorder(
            KfeStatementService statementService,
            KfeResponseMapper responseMapper) {
        this.statementService = statementService;
        this.responseMapper = responseMapper;
    }

    public void record(
            Long userId,
            KfeTransactionEntity tx,
            UUID walletId,
            KfeSubmitTransactionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>(responseMapper.buildDisplayPayload(tx, userId));
        if (request != null && request.memo() != null && !request.memo().isBlank()) {
            payload.put("memo", request.memo());
        }
        statementService.recordUserStatement(userId, walletId, tx, payload);
    }
}
