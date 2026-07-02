package source.kfe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeUserStatementEntity;
import source.kfe.repository.KfeUserStatementRepository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class KfeStatementService {

    private final KfeUserStatementRepository statementRepository;
    private final ObjectMapper objectMapper;

    public KfeStatementService(KfeUserStatementRepository statementRepository, ObjectMapper objectMapper) {
        this.statementRepository = statementRepository;
        this.objectMapper = objectMapper;
    }

    public void recordUserStatement(Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        KfeUserStatementEntity statement = new KfeUserStatementEntity();
        statement.setUserId(userId);
        statement.setWalletId(walletId);
        statement.setTransactionId(transaction.getId());
        statement.setDisplayPayloadJson(toJson(payload));
        statement.setExpiresAt(LocalDateTime.now().plusHours(24));
        statementRepository.save(statement);
    }

    public void recordUserStatementIfAbsent(Long userId, UUID walletId, KfeTransactionEntity transaction, Map<String, ?> payload) {
        if (statementRepository.existsByTransactionId(transaction.getId())) {
            return;
        }
        recordUserStatement(userId, walletId, transaction, payload);
    }

    private String toJson(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (Exception exception) {
            return "{}";
        }
    }
}
