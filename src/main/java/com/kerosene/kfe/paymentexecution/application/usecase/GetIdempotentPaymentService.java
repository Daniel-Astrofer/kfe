package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIdempotencyQueryPort;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

import java.util.Objects;
import java.util.Optional;

/** Read-only replay decision. A replay uses the current owned execution and never repeats financial effects. */
public final class GetIdempotentPaymentService {
    private final IdempotencyReservationStore reservations;
    private final PaymentIdempotencyQueryPort payments;

    public GetIdempotentPaymentService(IdempotencyReservationStore reservations, PaymentIdempotencyQueryPort payments) {
        this.reservations = reservations;
        this.payments = payments;
    }

    public Optional<PaymentExecutionResult> find(GetIdempotentPaymentQuery query) {
        Objects.requireNonNull(query, "idempotent payment query is required");
        var existing = Objects.requireNonNull(reservations.find(query.userId(), query.idempotencyKey()),
                "idempotency lookup result is required");
        if (existing.isEmpty()) { return Optional.empty(); }
        var reservation = existing.get();
        if (!query.idempotencyKey().equals(reservation.key())) {
            throw new IllegalStateException("Idempotency reservation key does not match the request.");
        }
        reservation.assertSameRequest(query.fingerprint());
        // Pending keeps the established retryable error; it is not permission to create another execution.
        var executionId = reservation.completedExecutionId();
        var payment = Objects.requireNonNull(payments.findOwnedByIdAndKey(query.userId(), executionId, query.idempotencyKey()),
                "idempotent execution lookup result is required")
                .orElseThrow(() -> new IllegalStateException("Idempotent transaction record is missing."));
        if (!executionId.value().equals(payment.id())) {
            throw new IllegalStateException("Idempotent transaction identity does not match the reservation.");
        }
        return Optional.of(payment);
    }
}
