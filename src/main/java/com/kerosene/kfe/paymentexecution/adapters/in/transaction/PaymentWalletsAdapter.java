package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.common.exception.FinancialSelfPaymentException;
import com.kerosene.kfe.paymentexecution.application.command.ResolvePaymentWalletsCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentWalletsUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentWalletSelection;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentWalletsService;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentSelfTransferRejected;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Preflight stays outside the financial transaction; wallet selection joins it for source locking. */
@Component
public class PaymentWalletsAdapter implements PaymentWalletsUseCase {
    private final PaymentWalletsService service;

    public PaymentWalletsAdapter(PaymentWalletsService service) { this.service = service; }

    @Override
    public UUID resolveDestinationReference(ResolvePaymentWalletsCommand command) {
        return service.resolveDestinationReference(command);
    }

    @Override
    public void requireNotSelfPayment(ResolvePaymentWalletsCommand command) {
        try { service.requireNotSelfPayment(command); }
        catch (PaymentSelfTransferRejected rejected) { throw new FinancialSelfPaymentException(); }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentWalletSelection resolve(ResolvePaymentWalletsCommand command) { return service.resolve(command); }
}
