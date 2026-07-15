package source.kfe.application.transaction;

import org.springframework.stereotype.Service;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.service.KfeStatementService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class KfeTransactionStatementRecorder {

    private final KfeStatementService statementService;

    public KfeTransactionStatementRecorder(KfeStatementService statementService) {
        this.statementService = statementService;
    }

    public void record(
            Long userId,
            KfeTransactionEntity tx,
            UUID walletId,
            KfeSubmitTransactionRequest request) {
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
        payload.put("displayBtcUsd", tx.getDisplayBtcUsd());
        payload.put("displayBtcEur", tx.getDisplayBtcEur());
        payload.put("displayBtcBrl", tx.getDisplayBtcBrl());
        payload.put("displayAmountUsd", tx.getDisplayAmountUsd());
        payload.put("displayAmountEur", tx.getDisplayAmountEur());
        payload.put("displayAmountBrl", tx.getDisplayAmountBrl());
        if (request != null && request.memo() != null && !request.memo().isBlank()) {
            payload.put("memo", request.memo());
        }
        statementService.recordUserStatement(userId, walletId, tx, payload);
    }
}
