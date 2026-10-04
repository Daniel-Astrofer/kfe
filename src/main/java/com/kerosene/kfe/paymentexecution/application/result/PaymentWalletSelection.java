package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentWalletSnapshot;

public record PaymentWalletSelection(PaymentWalletSnapshot source, PaymentWalletSnapshot destination) {
    public boolean requiresSourceReserve() { return source != null; }
}
