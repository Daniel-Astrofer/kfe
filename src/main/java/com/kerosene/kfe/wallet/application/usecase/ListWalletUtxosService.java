package com.kerosene.kfe.wallet.application.usecase;

import com.kerosene.kfe.wallet.application.command.ListWalletUtxosCommand;
import com.kerosene.kfe.wallet.application.port.in.ListWalletUtxosUseCase;
import com.kerosene.kfe.wallet.application.port.out.WalletChainPort;
import com.kerosene.kfe.wallet.application.port.out.WalletQueryPort;
import com.kerosene.kfe.wallet.application.result.WalletUtxoResult;
import com.kerosene.kfe.wallet.domain.service.WalletPolicy;

/** Read-only wallet projection; chain I/O remains behind WalletChainPort. */
public final class ListWalletUtxosService implements ListWalletUtxosUseCase {
    private final WalletQueryPort wallets;
    private final WalletChainPort chain;
    private final int descriptorScanRange;

    public ListWalletUtxosService(
            WalletQueryPort wallets,
            WalletChainPort chain,
            int descriptorScanRange) {
        this.wallets = wallets;
        this.chain = chain;
        this.descriptorScanRange = Math.max(1, descriptorScanRange);
    }

    @Override
    public WalletUtxoResult list(ListWalletUtxosCommand command) {
        var wallet = wallets.findOwned(command.ownerId(), command.walletId())
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        WalletPolicy.requireOwnedActive(wallet, command.ownerId(), command.walletId());
        WalletPolicy.requireBitcoinWallet(wallet);
        return new WalletUtxoResult(chain.listUnspent(
                wallet,
                wallets.activeAddresses(wallet.id()),
                descriptorScanRange));
    }
}
