package com.kerosene.kfe.ledger.adapters.out.statement;

import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;

/** Payment Execution projection adapter backed by the extracted Statement boundary. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LedgerPaymentStatementAdapter implements PaymentStatementPort {
    private final KfeTransactionRepository transactions;
    private final KfeResponseMapper mapper;
    private final KfeStatementService statements;

    public LedgerPaymentStatementAdapter(KfeTransactionRepository transactions,
            KfeResponseMapper mapper, KfeStatementService statements) {
        this.transactions = transactions;
        this.mapper = mapper;
        this.statements = statements;
    }

    @Override
    public void record(RecordPaymentStatementCommand command) {
        var transaction = transactions.findById(command.executionId().value())
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        var payload = new LinkedHashMap<String, Object>(mapper.buildDisplayPayload(transaction, command.userId()));
        if (command.memo() != null && !command.memo().isBlank()) {
            payload.put("memo", command.memo());
        }
        if (command.cancelled()) {
            payload.put("cancelled", true);
        }
        statements.recordUserStatement(command.userId(), command.walletId(), transaction, payload);
    }
}
