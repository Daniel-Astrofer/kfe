package com.kerosene.kfe.ledger.application.port.out;

import com.kerosene.kfe.ledger.domain.StatementRecord;

/** Statement projection port; implementations must join the financial transaction. */
public interface StatementPort {
    void upsert(StatementRecord record);
}
