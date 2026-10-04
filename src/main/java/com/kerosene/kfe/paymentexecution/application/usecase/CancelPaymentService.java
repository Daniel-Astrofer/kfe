package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentInvoiceCommand;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentCancellationHintsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationFencePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationNotificationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationQueryPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentInvoiceCancellationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationLockPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.RelatedPaymentLookupPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentCancellationHints;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentCancellationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Atomic cancellation orchestration. The inbound adapter owns the transaction; all decisions
 * below use immutable state read after the request lock and execution/outbox fence.
 */
public final class CancelPaymentService {
    private final PaymentCancellationQueryPort query;
    private final PaymentCancellationHintsUseCase hints;
    private final PaymentCancellationStatePort state;
    private final PaymentRequestCancellationStatePort requests;
    private final PaymentRequestCancellationLockPort requestLock;
    private final RelatedPaymentLookupPort related;
    private final PaymentCancellationFencePort fence;
    private final PaymentInvoiceCancellationPort invoices;
    private final PaymentRequestCancellationAuditPort requestAudit;
    private final CancelPaymentEffectsService effects;
    private final PaymentCancellationNotificationPort notifications;
    private final PaymentExecutionQueryRepository results;

    public CancelPaymentService(
            PaymentCancellationQueryPort query, PaymentCancellationHintsUseCase hints,
            PaymentCancellationStatePort state, PaymentRequestCancellationStatePort requests,
            PaymentRequestCancellationLockPort requestLock, RelatedPaymentLookupPort related,
            PaymentCancellationFencePort fence, PaymentInvoiceCancellationPort invoices,
            PaymentRequestCancellationAuditPort requestAudit, CancelPaymentEffectsService effects,
            PaymentCancellationNotificationPort notifications, PaymentExecutionQueryRepository results) {
        this.query = query;
        this.hints = hints;
        this.state = state;
        this.requests = requests;
        this.requestLock = requestLock;
        this.related = related;
        this.fence = fence;
        this.invoices = invoices;
        this.requestAudit = requestAudit;
        this.effects = effects;
        this.notifications = notifications;
        this.results = results;
    }

    public PaymentExecutionResult cancel(CancelPaymentCommand command) {
        long userId = command.userId();
        var id = command.paymentExecutionId();
        var visible = query.findParticipantVisible(userId, id)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        if (!id.equals(visible.executionId())) {
            throw new PaymentCancellationRejected();
        }
        if (visible.ownerUserId() != userId) {
            throw PaymentCancellationRejected.notEligible();
        }
        var eligibility = hints.hintsFor(userId, id);
        if (!eligibility.cancellable()) {
            throw PaymentCancellationRejected.notEligible();
        }
        if (PaymentCancellationHints.PAYMENT_REQUEST.equals(eligibility.cancelTarget())) {
            if (eligibility.paymentRequestId() == null) {
                throw new PaymentCancellationRejected();
            }
            cancelRequest(userId, eligibility.paymentRequestId(), id, false);
        } else if (PaymentCancellationHints.TRANSACTION.equals(eligibility.cancelTarget())) {
            fence.fence(List.of(id));
            var current = loadOwned(userId, id);
            if (!current.cancellable()) {
                throw new PaymentCancellationRejected();
            }
            effects.cancel(id, "Cancelado pelo usuário.");
        } else {
            throw new PaymentCancellationRejected();
        }
        notifications.publishAfterCommit(userId);
        return results.findParticipantVisibleById(userId, id)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
    }

    public UUID cancelPaymentRequest(CancelPaymentRequestCommand command) {
        return cancelRequest(command.userId(), command.paymentRequestId(), null, true);
    }

    private UUID cancelRequest(long userId, UUID requestId, PaymentExecutionId explicit, boolean notify) {
        // Observers follow the same order: request -> outbox -> execution -> financial effects.
        requestLock.lock(userId, requestId);
        var request = requests.load(userId, requestId);
        if (!requestId.equals(request.id()) || request.userId() != userId) {
            throw new PaymentCancellationRejected();
        }
        if (!request.cancellable()) {
            if (explicit != null) {
                throw new PaymentCancellationRejected();
            }
            return requestId;
        }

        var ids = new LinkedHashSet<>(related.findRelated(userId, requestId));
        if (explicit != null) {
            ids.add(explicit);
        }
        fence.fence(List.copyOf(ids));
        var snapshots = new ArrayList<PaymentCancellationSnapshot>(ids.size());
        for (var id : ids) {
            var current = loadOwned(userId, id);
            // Validate the entire batch before any RPC, audit or financial effect.
            if (current.status() == ExecutionStatus.SETTLED
                    || (current.incomplete() && !current.cancellable())
                    || (id.equals(explicit) && !current.cancellable())) {
                throw new PaymentCancellationRejected();
            }
            snapshots.add(current);
        }

        cancelInvoice(request);
        requests.markCancelled(request);
        requestAudit.recordCancelled(request);
        for (var current : snapshots) {
            if (current.incomplete()) {
                effects.cancel(current.executionId(), "Invoice/link de pagamento cancelado pelo usuário.");
            }
        }
        if (notify) {
            notifications.publishAfterCommit(userId);
        }
        return requestId;
    }

    private PaymentCancellationSnapshot loadOwned(long userId, PaymentExecutionId id) {
        var current = state.load(id);
        if (!id.equals(current.executionId()) || current.userId() != userId) {
            throw new PaymentCancellationRejected();
        }
        return current;
    }

    private void cancelInvoice(PaymentRequestCancellationSnapshot request) {
        if (!request.invoiceCancellationRequired()) {
            return;
        }
        try {
            if (!invoices.cancel(new CancelPaymentInvoiceCommand(
                    request.userId(), request.paymentHash(), request.providerReference(), request.paymentRequest()))) {
                throw new IllegalStateException("O provedor não confirmou o cancelamento da invoice Lightning.");
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Não foi possível confirmar o cancelamento da invoice Lightning.", exception);
        }
    }
}
