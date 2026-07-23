package com.kerosene.kfe.application.transaction;

import org.springframework.stereotype.Service;
import com.kerosene.kfe.dto.KfeSubmitTransactionRequest;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.service.KfeResponseMapper;
import com.kerosene.kfe.service.KfeStatementService;

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
        // Join the submit TX (REQUIRED) so FK to transactions_master succeeds before commit.
        statementService.recordUserStatement(userId, walletId, tx, payload);
    }
}
