package com.kerosene.kfe.wallet.application.usecase;

import com.kerosene.kfe.wallet.application.command.CreateColdPsbtCommand;
import com.kerosene.kfe.wallet.application.port.in.CreateColdPsbtUseCase;
import com.kerosene.kfe.wallet.application.port.out.WalletApprovalPort;
import com.kerosene.kfe.wallet.application.port.out.WalletAuditPort;
import com.kerosene.kfe.wallet.application.port.out.WalletChangeAddressPort;
import com.kerosene.kfe.wallet.application.port.out.WalletChainPort;
import com.kerosene.kfe.wallet.application.port.out.WalletDestinationPort;
import com.kerosene.kfe.wallet.application.port.out.WalletHashPort;
import com.kerosene.kfe.wallet.application.port.out.WalletPsbtPort;
import com.kerosene.kfe.wallet.application.port.out.WalletQueryPort;
import com.kerosene.kfe.wallet.application.port.out.WalletWorkflowPort;
import com.kerosene.kfe.wallet.application.result.ColdPsbtResult;
import com.kerosene.kfe.wallet.domain.service.WalletPolicy;

import java.util.Map;

/** Pure orchestration of cold custody intent; all side effects are explicit ports. */
public final class CreateColdPsbtService implements CreateColdPsbtUseCase {
    private final WalletQueryPort wallets;
    private final WalletChainPort chain;
    private final WalletApprovalPort approval;
    private final WalletDestinationPort destination;
    private final WalletChangeAddressPort changeAddress;
    private final WalletPsbtPort psbt;
    private final WalletWorkflowPort workflows;
    private final WalletHashPort hash;
    private final WalletAuditPort audit;
    private final int descriptorScanRange;

    public CreateColdPsbtService(
            WalletQueryPort wallets,
            WalletChainPort chain,
            WalletApprovalPort approval,
            WalletDestinationPort destination,
            WalletChangeAddressPort changeAddress,
            WalletPsbtPort psbt,
            WalletWorkflowPort workflows,
            WalletHashPort hash,
            WalletAuditPort audit,
            int descriptorScanRange) {
        this.wallets = wallets;
        this.chain = chain;
        this.approval = approval;
        this.destination = destination;
        this.changeAddress = changeAddress;
        this.psbt = psbt;
        this.workflows = workflows;
        this.hash = hash;
        this.audit = audit;
        this.descriptorScanRange = Math.max(1, descriptorScanRange);
    }

    @Override
    public ColdPsbtResult create(CreateColdPsbtCommand command) {
        var wallet = wallets.findOwned(command.ownerId(), command.walletId())
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        WalletPolicy.requireOwnedActive(wallet, command.ownerId(), command.walletId());
        WalletPolicy.requireBitcoinWallet(wallet);
        WalletPolicy.requireColdPsbtWallet(wallet);
        String destinationAddress = WalletPolicy.requireDestination(command.request());
        destination.requireValidBitcoinAddress(destinationAddress);
        approval.approveColdPsbt(command.ownerId(), command.approvalFactor());

        var live = chain.listUnspent(
                wallet,
                wallets.activeAddresses(wallet.id()),
                descriptorScanRange);
        var selected = WalletPolicy.selectOwnedInputs(live, command.request().requestedInputs());
        String change = changeAddress.issueChangeAddress(wallet);
        var funded = psbt.create(
                selected.stream().map(utxo -> utxo.outpoint()).toList(),
                destinationAddress,
                command.request().amountSats(),
                command.request().confirmationTarget(),
                command.request().feeRateSatsPerVbyte(),
                change);
        String psbtHash = hash.sha256(funded.psbt());
        var inputOutpoints = selected.stream().map(utxo -> utxo.outpoint()).toList();
        var workflowId = workflows.create(
                command.ownerId(),
                wallet.id(),
                funded.psbt(),
                psbtHash,
                funded.feeSats(),
                command.request().amountSats(),
                destinationAddress,
                inputOutpoints);
        audit.record(
                "KFE_COLD_WALLET_PSBT_CREATED",
                wallet.id(),
                Map.of(
                        "walletId", wallet.id().toString(),
                        "psbtHash", psbtHash,
                        "amountSats", Long.toString(command.request().amountSats()),
                        "feeSats", Long.toString(funded.feeSats()),
                        "inputCount", Integer.toString(inputOutpoints.size())));
        return new ColdPsbtResult(
                workflowId,
                funded.psbt(),
                psbtHash,
                funded.feeSats(),
                command.request().amountSats(),
                destinationAddress,
                inputOutpoints);
    }
}
