package com.kerosene.kfe.wallet.application.command;

import java.util.UUID;

public record UpdateWalletCommand(long ownerId, UUID walletId, String label) {
}
