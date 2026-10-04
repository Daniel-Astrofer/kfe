package com.kerosene.kfe.wallet.application.command;

import com.kerosene.kfe.wallet.domain.model.ColdPsbtRequest;

import java.util.UUID;

public record CreateColdPsbtCommand(
        long ownerId,
        UUID walletId,
        ColdPsbtRequest request,
        String approvalFactor) {
}
