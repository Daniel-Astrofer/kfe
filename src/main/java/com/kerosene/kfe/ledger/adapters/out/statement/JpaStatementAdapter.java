package com.kerosene.kfe.ledger.adapters.out.statement;

import com.kerosene.kfe.ledger.application.port.out.StatementPort;
import com.kerosene.kfe.ledger.domain.StatementRecord;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Compatibility adapter for the existing 24-hour participant projection. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaStatementAdapter implements StatementPort {
    private final KfeTransactionRepository transactions;
    private final KfeStatementService statements;

    public JpaStatementAdapter(KfeTransactionRepository transactions, KfeStatementService statements) {
        this.transactions = transactions;
        this.statements = statements;
    }

    @Override
    public void upsert(StatementRecord record) {
        var transaction = transactions.findById(record.transactionId())
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found"));
        statements.recordUserStatement(record.userId(), record.walletId(), transaction, record.payload());
    }
}
