package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.PaymentSettlementGateCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSettlementGateResult;

/** Internal submit stage, not an independently authorized payment or retry endpoint. */
public interface PaymentSettlementGateUseCase {
    PaymentSettlementGateResult requirePass(PaymentSettlementGateCommand command);
}
