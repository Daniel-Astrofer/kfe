package com.kerosene.kfe.paymentexecution.adapters.out.statement;

import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** Preserves the existing statement upsert, display projection and after-commit publication. */
@Transactional(propagation = Propagation.MANDATORY)
@Deprecated(forRemoval = false)
public class LegacyPaymentStatementAdapter implements PaymentStatementPort {

    private final KfeTransactionRepository repository;
    private final KfeResponseMapper responseMapper;
    private final KfeStatementService statementService;

    public LegacyPaymentStatementAdapter(
            KfeTransactionRepository repository,
            KfeResponseMapper responseMapper,
            KfeStatementService statementService) {
        this.repository = repository;
        this.responseMapper = responseMapper;
        this.statementService = statementService;
    }

    @Override
    public void record(RecordPaymentStatementCommand command) {
        var tx = repository.findById(command.executionId().value())
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        Map<String, Object> payload = new LinkedHashMap<>(
                responseMapper.buildDisplayPayload(tx, command.userId()));
        if (command.memo() != null && !command.memo().isBlank()) {
            payload.put("memo", command.memo());
        }
        if (command.cancelled()) {
            payload.put("cancelled", true);
        }
        statementService.recordUserStatement(command.userId(), command.walletId(), tx, payload);
    }
}
