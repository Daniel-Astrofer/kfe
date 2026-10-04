package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.CancellationEligibilitySnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import java.util.Optional;

/** Independent read projection: must enforce participant visibility and request ownership in SQL. */
public interface PaymentCancellationQueryPort {
    Optional<CancellationEligibilitySnapshot> findParticipantVisible(long userId, PaymentExecutionId executionId);
}
