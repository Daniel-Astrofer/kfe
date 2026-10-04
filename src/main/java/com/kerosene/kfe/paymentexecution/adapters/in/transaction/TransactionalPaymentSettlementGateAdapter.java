package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.PaymentSettlementGateCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentSettlementGateUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSettlementGateResult;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentSettlementGateService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalPaymentSettlementGateAdapter implements PaymentSettlementGateUseCase {
    private final PaymentSettlementGateService service;
    public TransactionalPaymentSettlementGateAdapter(PaymentSettlementGateService service) { this.service = service; }
    @Override
    public PaymentSettlementGateResult requirePass(PaymentSettlementGateCommand command) { return service.requirePass(command); }
}
