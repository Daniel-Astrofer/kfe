package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentApprovalPort;
import com.kerosene.kfe.paymentexecution.domain.exception.MissingLocalPaymentFactor;
import com.kerosene.kfe.paymentexecution.domain.policy.PaymentAuthorizationPolicy;

import java.util.Objects;

/** Chooses the existing approval policy without transport errors or credential conversion. */
public final class AuthorizePaymentService {
    private final PaymentApprovalPort approval;
    private final PaymentAuthorizationPolicy policy = new PaymentAuthorizationPolicy();

    public AuthorizePaymentService(PaymentApprovalPort approval) {
        this.approval = Objects.requireNonNull(approval, "payment approval port is required");
    }

    public void authorize(SubmitPaymentCommand command) {
        Objects.requireNonNull(command, "payment command is required");
        switch (policy.requirementFor(command.rail(), command.direction())) {
            case NONE -> { return; }
            case LOCAL_FACTOR_AND_ASSERTION -> {
                if (command.appPin() == null || command.appPin().isBlank()) { throw new MissingLocalPaymentFactor(); }
            }
            case WALLET_OUTBOUND_STEP_UP -> { }
        }
        approval.approve(command);
    }
}
