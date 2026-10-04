package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.AuthorizePaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.usecase.AuthorizePaymentService;
import com.kerosene.kfe.paymentexecution.domain.exception.MissingLocalPaymentFactor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/** Authorization must finish before opening the financial transaction, including direct calls without a proxy. */
@Component
public class PaymentAuthorizationAdapter implements AuthorizePaymentUseCase {
    private final AuthorizePaymentService service;

    public PaymentAuthorizationAdapter(AuthorizePaymentService service) {
        this.service = service;
    }

    @Override
    public void authorize(SubmitPaymentCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Payment authorization must start outside an existing transaction.");
        }
        try {
            service.authorize(command);
        } catch (MissingLocalPaymentFactor missing) {
            throw new StructuredPlatformException(missing.getMessage(), HttpStatus.UNAUTHORIZED,
                    ErrorCodes.AUTH_TRANSACTIONAL_AUTH_REQUIRED,
                    Map.of("requiredAllOf", new String[] {"appPin", "passkeyAssertionJson"},
                            "missing", new String[] {"appPin"}));
        }
    }
}
