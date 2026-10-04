package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.in.PaymentCancellationHintsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationQueryPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentCancellationHints;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecution;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Read-only eligibility, independent of cancellation effects, persistence and response mappers. */
public final class PaymentCancellationHintsService implements PaymentCancellationHintsUseCase {
    private final PaymentCancellationQueryPort query;

    public PaymentCancellationHintsService(PaymentCancellationQueryPort query) {
        this.query = query;
    }

    @Override
    public PaymentCancellationHints hintsFor(long userId, PaymentExecutionId executionId) {
        if (userId <= 0L || executionId == null) {
            return PaymentCancellationHints.none();
        }
        var candidate = query.findParticipantVisible(userId, executionId).orElse(null);
        // Participant visibility is read permission, not permission to cancel another owner's funds.
        if (candidate == null || !executionId.equals(candidate.executionId()) || candidate.ownerUserId() != userId) {
            return PaymentCancellationHints.none();
        }
        var request = candidate.paymentRequest();
        if (request != null) {
            if (request.id() == null || request.userId() != userId) {
                return PaymentCancellationHints.none();
            }
            boolean cancellable = request.status() != null && request.status().cancellable();
            return new PaymentCancellationHints(cancellable,
                    cancellable ? PaymentCancellationHints.PAYMENT_REQUEST : null,
                    request.id(), request.publicId(), request.status() == null ? null : request.status().name());
        }
        if (candidate.status() != null && PaymentExecution.reconstitute(executionId, candidate.status())
                .canBeCancelled(candidate.blockchainTransactionId())) {
            return new PaymentCancellationHints(true, PaymentCancellationHints.TRANSACTION, null, null, null);
        }
        return PaymentCancellationHints.none();
    }
}
