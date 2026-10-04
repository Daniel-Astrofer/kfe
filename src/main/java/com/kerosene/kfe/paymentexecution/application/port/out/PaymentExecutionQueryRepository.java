package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Participant-scoped read repository; adapters enforce visibility in the persistence query. */
public interface PaymentExecutionQueryRepository {

    Optional<PaymentExecutionResult> findParticipantVisibleById(
            long userId,
            PaymentExecutionId paymentExecutionId);

    List<PaymentExecutionResult> findParticipantVisible(
            long userId,
            int page,
            int size,
            Instant since);
}
