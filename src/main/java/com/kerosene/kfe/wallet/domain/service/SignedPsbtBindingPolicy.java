package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.exception.WalletRuleViolation;
import com.kerosene.kfe.wallet.domain.model.Outpoint;
import com.kerosene.kfe.wallet.domain.model.SignedPsbt;

import java.util.List;

/** Prevents signed-PSBT substitution after a cold-wallet workflow was approved. */
public final class SignedPsbtBindingPolicy {
    private SignedPsbtBindingPolicy() {
    }

    public static void requireMatches(
            SignedPsbt signed,
            String expectedDestination,
            long expectedAmountSats,
            List<Outpoint> allowedInputs) {
        if (signed == null || signed.inputs().isEmpty()) {
            throw new WalletRuleViolation("Signed PSBT has no inputs.");
        }
        if (expectedDestination == null || expectedDestination.isBlank() || expectedAmountSats <= 0) {
            throw new WalletRuleViolation("Workflow is missing destination/amount binding.");
        }
        if (allowedInputs != null && !allowedInputs.isEmpty()) {
            for (Outpoint input : signed.inputs()) {
                if (!allowedInputs.contains(input)) {
                    throw new WalletRuleViolation(
                            "Signed PSBT spends an input that was not part of the approved workflow.");
                }
            }
        }
        boolean destinationPaid = signed.outputs().stream().anyMatch(output ->
                output != null
                        && output.address() != null
                        && expectedDestination.trim().equalsIgnoreCase(output.address().trim())
                        && output.valueSats() == expectedAmountSats);
        if (!destinationPaid) {
            throw new WalletRuleViolation("Signed PSBT does not pay the approved destination/amount.");
        }
    }
}
