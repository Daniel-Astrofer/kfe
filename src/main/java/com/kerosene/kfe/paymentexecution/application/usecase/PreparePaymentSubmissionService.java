package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.*;
import com.kerosene.kfe.paymentexecution.application.port.in.*;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentSubmission;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import java.util.Map;

/** Prepare exactly once within the submit that authorized and created the intent. Never reserves or dispatches. */
public final class PreparePaymentSubmissionService {
    private final PaymentSubmissionStatePort state;
    private final PaymentWalletsUseCase wallets;
    private final PreparePaymentPricingUseCase pricing;
    private final PaymentProposalHashPort hasher;
    private final PaymentSettlementGateUseCase gate;
    private final PaymentExecutionLifecycleUseCase lifecycle;
    private final PaymentSubmissionTelemetryPort telemetry;

    public PreparePaymentSubmissionService(PaymentSubmissionStatePort state, PaymentWalletsUseCase wallets,
            PreparePaymentPricingUseCase pricing, PaymentProposalHashPort hasher, PaymentSettlementGateUseCase gate,
            PaymentExecutionLifecycleUseCase lifecycle, PaymentSubmissionTelemetryPort telemetry) {
        this.state = state; this.wallets = wallets; this.pricing = pricing; this.hasher = hasher;
        this.gate = gate; this.lifecycle = lifecycle; this.telemetry = telemetry;
    }

    public PreparedPaymentSubmission prepare(PreparePaymentSubmissionCommand command) {
        var intent = state.lockAndLoad(command.userId(), command.executionId());
        intent.requireReadyFor(command.userId(), command.executionId(), command.externalReference(), command.paymentRequestPublicId());
        var id = intent.executionId();
        var validating = lifecycle.transition(id, ExecutionStatus.VALIDATING, "KFE_TRANSACTION_VALIDATING",
                Map.of("requestHash", command.requestFingerprint().value()));
        requireConfirmed(validating, id, ExecutionStatus.INTENT, ExecutionStatus.VALIDATING);
        var selection = wallets.resolve(new ResolvePaymentWalletsCommand(intent.userId(), intent.rail(), intent.direction(),
                intent.sourceWalletId(), intent.destinationWalletId(), command.externalReference()));
        var preparedPricing = pricing.prepare(new PreparePaymentPricingCommand(intent.rail(), intent.direction(),
                intent.amountSats(), command.requestedNetworkFeeSats(), command.feeRateSatPerVbyte(), command.feeTargetBlocks()));
        long reservedFee = preparedPricing.reservedNetworkFeeSats();
        long clientFee = Math.max(0L, command.requestedNetworkFeeSats());
        if (reservedFee > clientFee) {
            telemetry.feeReserveRaised(clientFee, reservedFee, command.feeRateSatPerVbyte(), command.feeTargetBlocks());
        }
        state.applyPricing(intent, preparedPricing);
        var quote = preparedPricing.quote();
        var proposalHash = hasher.hash(new PaymentProposal(id, intent.userId(), intent.rail(), intent.direction(),
                intent.sourceWalletId(), intent.destinationWalletId(), quote.grossAmountSats(), quote.receiverAmountSats(),
                quote.networkFeeSats(), quote.keroseneFeeSats(), quote.totalDebitSats(),
                command.externalReference(), command.paymentRequestPublicId()));
        if (proposalHash == null || proposalHash.isBlank()) { throw new IllegalStateException("Payment proposal hash was not produced."); }
        state.recordProposal(intent.userId(), id, proposalHash);
        var evidence = gate.requirePass(new PaymentSettlementGateCommand(intent.userId(), id,
                selection.source() != null ? selection.source().id() : intent.sourceWalletId(), intent.idempotencyKey(),
                true, intent.rail(), intent.direction(), intent.amountSats(), reservedFee,
                quote.totalDebitSats(), selection.requiresSourceReserve(), proposalHash));
        if (evidence == null) { throw new IllegalStateException("Payment settlement gate returned no evidence."); }
        var ready = lifecycle.transition(id, ExecutionStatus.QUORUM_SYNC, "KFE_TRANSACTION_QUORUM_SYNC",
                Map.of("proposalHash", proposalHash, "settlementGatePassed", 1, "quorumAckCount", evidence.quorumAckCount()));
        requireConfirmed(ready, id, ExecutionStatus.VALIDATING, ExecutionStatus.QUORUM_SYNC);
        state.recordQuorum(intent.userId(), id, evidence.quorumAckCount());
        return new PreparedPaymentSubmission(ready, selection.destination());
    }

    private static void requireConfirmed(PaymentExecutionStatusChanged event, PaymentExecutionId id,
            ExecutionStatus previous, ExecutionStatus target) {
        if (event == null || !id.equals(event.executionId()) || event.previousStatus() != previous || event.currentStatus() != target) {
            throw new IllegalStateException("Payment submission transition was not confirmed.");
        }
    }
}
