package source.kfe.rail;

import org.springframework.stereotype.Component;

@Component("bitcoinCorePsbtKfeOnchainPaymentGateway")
public class KfeOnchainCustodyAdapter implements KfeOnchainPaymentGateway {

    private final KfeQuorumPsbtSigningService quorumPsbtSigningService;

    public KfeOnchainCustodyAdapter(KfeQuorumPsbtSigningService quorumPsbtSigningService) {
        this.quorumPsbtSigningService = quorumPsbtSigningService;
    }

    @Override
    public String providerName() {
        return "BITCOIN_CORE_QUORUM";
    }

    @Override
    public OnchainFundingPreflight preflightOnchain(OnchainPreflightCommand command) {
        KfeQuorumPsbtSigningService.OnchainFundingPreflight preflight = quorumPsbtSigningService.preflight(command);
        return new OnchainFundingPreflight(
                true,
                preflight.feeSats(),
                preflight.psbtHash(),
                preflight.configuredSignerCount(),
                providerName());
    }

    @Override
    public PaymentResult sendOnchain(OnchainPaymentCommand command) {
        KfeQuorumPsbtSigningService.OnchainExecution result = quorumPsbtSigningService.execute(command);
        return new PaymentResult(
                result.txid(),
                result.txid(),
                null,
                "MEMPOOL",
                result.feeSats(),
                result.metadataJson());
    }
}
