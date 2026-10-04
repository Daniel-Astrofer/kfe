package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentWalletSnapshot;

/** Result of this submit's preparation, not a serializable approval or retry ticket. */
public record PreparedPaymentSubmission(PaymentExecutionStatusChanged transition, PaymentWalletSnapshot destinationWallet) {}
