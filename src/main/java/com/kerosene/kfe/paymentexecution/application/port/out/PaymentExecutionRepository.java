package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecution;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.Optional;

/** Write-side repository for the PaymentExecution aggregate. */
public interface PaymentExecutionRepository {

    Optional<PaymentExecution> findById(PaymentExecutionId id);

    void save(PaymentExecution execution);
}
