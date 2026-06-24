package source.kfe.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import source.common.infra.logging.LogSanitizer;
import source.common.service.AddressDerivationService;
import source.kfe.rail.BitcoinCoreRpcClient;

@Service
public class KfeReceiveAddressIssuer {

    private final AddressDerivationService addressDerivationService;
    private final KfeDerivationCursorService cursorService;
    private final BitcoinCoreRpcClient bitcoinCoreRpcClient;
    private final String platformMasterXpub;
    private final boolean bitcoinCoreWalletAddressEnabled;

    public KfeReceiveAddressIssuer(
            AddressDerivationService addressDerivationService,
            KfeDerivationCursorService cursorService,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            @Value("${bitcoin.platform.master-xpub:${bitcoin.hot-wallet.xpub:}}") String platformMasterXpub,
            @Value("${kfe.receive.bitcoin-core-wallet-address-enabled:false}")
            boolean bitcoinCoreWalletAddressEnabled) {
        this.addressDerivationService = addressDerivationService;
        this.cursorService = cursorService;
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient.getIfAvailable();
        this.platformMasterXpub = platformMasterXpub != null ? platformMasterXpub.trim() : "";
        this.bitcoinCoreWalletAddressEnabled = bitcoinCoreWalletAddressEnabled;
    }

    public boolean canIssue() {
        return !platformMasterXpub.isBlank() || (bitcoinCoreWalletAddressEnabled && bitcoinCoreRpcClient != null);
    }

    public IssuedAddress issue(String label) {
        if (!platformMasterXpub.isBlank()) {
            int index = cursorService.nextIndex(KfeDerivationCursorService.KFE_BIP84_EXTERNAL);
            AddressDerivationService.DerivedAddress derived =
                    addressDerivationService.deriveAddressDetailsFromXpub(platformMasterXpub, index);
            return new IssuedAddress(
                    derived.address(),
                    "m/84'/0'/0'/0/" + derived.index(),
                    derived.index(),
                    "KFE_PLATFORM_XPUB:" + LogSanitizer.fingerprint(platformMasterXpub));
        }

        if (bitcoinCoreWalletAddressEnabled && bitcoinCoreRpcClient != null) {
            String address = bitcoinCoreRpcClient.getNewAddress(label != null ? label : "kfe-receive");
            return new IssuedAddress(
                    address,
                    "bitcoin-core-wallet:" + bitcoinCoreRpcClient.walletName(),
                    -1,
                    "KFE_BITCOIN_CORE_WALLET");
        }

        throw new IllegalStateException(
                "KFE receive address issuance requires bitcoin.platform.master-xpub or Bitcoin Core wallet issuance.");
    }

    public record IssuedAddress(
            String address,
            String derivationPath,
            int derivationIndex,
            String providerReference) {
    }
}
