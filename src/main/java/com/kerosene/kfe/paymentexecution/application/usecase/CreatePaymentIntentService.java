package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.CreatePaymentIntentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CreatePaymentIntentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIntentStore;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentIntent;

/** Normalizes the existing intent representation without carrying persistence or transport types. */
public final class CreatePaymentIntentService implements CreatePaymentIntentUseCase {

    private final PaymentIntentStore store;

    public CreatePaymentIntentService(PaymentIntentStore store) {
        this.store = store;
    }

    @Override
    public PaymentExecutionId create(CreatePaymentIntentCommand command) {
        String publicId = clean(command.paymentRequestPublicId());
        var intent = new PaymentIntent(
                command.userId(), command.idempotencyKey(), command.rail(), command.direction(),
                command.sourceWalletId(), command.destinationWalletId(), command.amountSats(),
                publicId != null ? publicId : clean(command.externalReference()), clean(command.memo()));
        return store.create(intent);
    }

    private static String clean(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
