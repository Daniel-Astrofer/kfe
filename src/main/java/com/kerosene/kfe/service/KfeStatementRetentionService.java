package com.kerosene.kfe.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.repository.KfeUserStatementRepository;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class KfeStatementRetentionService {

    private KfeMaintenanceGuard maintenanceGuard = KfeMaintenanceGuard.unavailable();

    @Autowired
    public void setMaintenanceGuard(KfeMaintenanceGuard guard) {
        this.maintenanceGuard = java.util.Objects.requireNonNull(guard);
    }

    private final KfeUserStatementRepository statementRepository;

    public KfeStatementRetentionService(KfeUserStatementRepository statementRepository) {
        this.statementRepository = statementRepository;
    }

    @Scheduled(fixedDelayString = "${kfe.statement.cleanup-delay-ms:3600000}")
    @Transactional
    public void purgeExpiredStatements() {
        maintenanceGuard.executeMutation("statement.purge-expired", () -> {
            statementRepository.deleteByExpiresAtBefore(LocalDateTime.now(ZoneOffset.UTC));
            return Boolean.TRUE;
        }, ignored -> true);
    }
}
