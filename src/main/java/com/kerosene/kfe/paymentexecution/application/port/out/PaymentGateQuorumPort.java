package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.SettlementQuorumEvidence;

public interface PaymentGateQuorumPort {
    SettlementQuorumEvidence requireConsensus(String proposalHash);
}
