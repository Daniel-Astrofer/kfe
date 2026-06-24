package source.kfe.application.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.model.KfeExecutionOutboxEntity;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.repository.KfeExecutionOutboxRepository;
import source.kfe.service.KfeHashService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class KfeTransactionOutboxUseCase {

    private final KfeExecutionOutboxRepository outboxRepository;
    private final KfeHashService hashService;
    private final ObjectMapper objectMapper;

    public KfeTransactionOutboxUseCase(
            KfeExecutionOutboxRepository outboxRepository,
            KfeHashService hashService,
            ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.hashService = hashService;
        this.objectMapper = objectMapper;
    }

    public void enqueueExternal(KfeTransactionEntity tx, KfeSubmitTransactionRequest request) {
        String payloadJson = outboxPayload(tx, request);
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        outbox.setTransactionId(tx.getId());
        outbox.setOperation(tx.getRail().name() + "_" + tx.getDirection().name());
        outbox.setPayloadJson(payloadJson);
        outbox.setPayloadHash(hashService.sha256(payloadJson));
        outbox.setNextAttemptAt(LocalDateTime.now());
        outboxRepository.save(outbox);
    }

    private String outboxPayload(KfeTransactionEntity tx, KfeSubmitTransactionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", tx.getId().toString());
        payload.put("idempotencyKey", tx.getIdempotencyKey());
        payload.put("userId", tx.getUserId());
        payload.put("rail", tx.getRail().name());
        payload.put("direction", tx.getDirection().name());
        payload.put("sourceWalletId", tx.getSourceWalletId());
        payload.put("destinationWalletId", tx.getDestinationWalletId());
        payload.put("amountSats", tx.getReceiverAmountSats());
        payload.put("networkFeeSats", tx.getNetworkFeeSats());
        payload.put("totalDebitSats", tx.getTotalDebitSats());
        payload.put("externalReference", request.externalReference());
        payload.put("memo", request.memo());
        payload.put("quorumProposalHash", tx.getQuorumProposalHash());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize KFE outbox payload.", exception);
        }
    }
}
