package source.kfe.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.repository.KfeUserStatementRepository;

import java.time.LocalDateTime;

@Service
public class KfeStatementRetentionService {

    private final KfeUserStatementRepository statementRepository;

    public KfeStatementRetentionService(KfeUserStatementRepository statementRepository) {
        this.statementRepository = statementRepository;
    }

    @Scheduled(fixedDelayString = "${kfe.statement.cleanup-delay-ms:3600000}")
    @Transactional
    public void purgeExpiredStatements() {
        statementRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }
}
