package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.ResolvePaymentWalletsCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentWalletSelection;
import java.util.UUID;

public interface PaymentWalletsUseCase {
    UUID resolveDestinationReference(ResolvePaymentWalletsCommand command);
    void requireNotSelfPayment(ResolvePaymentWalletsCommand command);
    PaymentWalletSelection resolve(ResolvePaymentWalletsCommand command);
}
