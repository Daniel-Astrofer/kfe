package source.kfe.service;

import org.springframework.stereotype.Component;
import source.kfe.dto.KfeAddressResponse;
import source.kfe.dto.KfeTransactionResponse;
import source.kfe.dto.KfeWalletResponse;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletAddressStatus;
import source.kfe.model.KfeWalletKind;
import source.kfe.repository.KfeWalletAddressRepository;

@Component
public class KfeResponseMapper {

    private final KfeWalletAddressRepository addressRepository;

    public KfeResponseMapper(KfeWalletAddressRepository addressRepository) {
        this.addressRepository = addressRepository;
    }

    public KfeWalletResponse toWalletResponse(KfeWalletEntity wallet) {
        String activeAddress = addressRepository
                .findTopByWalletIdAndStatusOrderByCreatedAtDesc(wallet.getId(), KfeWalletAddressStatus.ACTIVE)
                .map(KfeWalletAddressEntity::getAddress)
                .orElse(null);
        return new KfeWalletResponse(
                wallet.getId(),
                wallet.getKind(),
                wallet.getStatus(),
                wallet.getLabel(),
                wallet.getLabel(),
                walletTypeDescription(wallet.getKind()),
                wallet.getAsset(),
                wallet.isSpendable(),
                hasText(wallet.getXpub()),
                hasText(wallet.getMpcPublicKey()),
                activeAddress,
                wallet.getCreatedAt(),
                wallet.getUpdatedAt());
    }

    public KfeAddressResponse toAddressResponse(KfeWalletAddressEntity address) {
        return new KfeAddressResponse(
                address.getId(),
                address.getWalletId(),
                address.getAddress(),
                address.getAddressRole(),
                address.getStatus(),
                address.getDerivationPath(),
                address.getDerivationIndex(),
                address.getProviderReference(),
                address.getCreatedAt(),
                address.getRetiredAt());
    }

    public KfeTransactionResponse toTransactionResponse(KfeTransactionEntity tx) {
        return new KfeTransactionResponse(
                tx.getId(),
                tx.getStatus(),
                tx.getRail(),
                tx.getDirection(),
                tx.getSourceWalletId(),
                tx.getDestinationWalletId(),
                tx.getGrossAmountSats(),
                tx.getReceiverAmountSats(),
                tx.getNetworkFeeSats(),
                tx.getKeroseneFeeSats(),
                tx.getTotalDebitSats(),
                tx.getDisplayBtcUsd(),
                tx.getDisplayBtcEur(),
                tx.getDisplayBtcBrl(),
                tx.getDisplayAmountUsd(),
                tx.getDisplayAmountEur(),
                tx.getDisplayAmountBrl(),
                tx.getQuorumProposalHash(),
                tx.getQuorumAckCount(),
                tx.getProviderReference(),
                tx.getBlockchainTxid(),
                tx.getConfirmations(),
                tx.getFailureCode(),
                tx.getFailureMessage(),
                tx.getCreatedAt(),
                tx.getUpdatedAt());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public String walletTypeDescription(KfeWalletKind kind) {
        if (kind == null) {
            return "Conta Assegurada";
        }
        return switch (kind) {
            case INTERNAL -> "Carteira assegurada pela Kerosene: saldo interno lastreado na carteira de quorum.";
            case CUSTODIAL_ONCHAIN -> "Custodial on-chain: carteira separada monitorada pela Kerosene, com assinatura sob custódia.";
            case WATCH_ONLY -> "Cold wallet: saldo observado por xpub/descriptor; fundos não são saldo Kerosene e não são movimentados pelo servidor.";
            case SYSTEM_FUNDS -> "Fundos Globais";
            case SYSTEM_PROFIT -> "Lucro Kerosene";
        };
    }
}
