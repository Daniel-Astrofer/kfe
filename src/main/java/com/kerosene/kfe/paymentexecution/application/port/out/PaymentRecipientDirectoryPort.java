package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.PaymentRecipient;
import java.util.Optional;

public interface PaymentRecipientDirectoryPort {
    Optional<PaymentRecipient> findByUsername(String username);
}
