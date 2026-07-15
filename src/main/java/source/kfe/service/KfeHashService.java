package source.kfe.service;

import org.springframework.stereotype.Service;
import source.kfe.model.KfeBalanceEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class KfeHashService {

    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value != null ? value : "")
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public String initialBalanceHash(String walletId, String asset) {
        return sha256("KFE_BALANCE_GENESIS|" + walletId + "|" + asset);
    }

    public String balanceHash(KfeBalanceEntity balance) {
        return sha256(String.join("|",
                "KFE_BALANCE",
                String.valueOf(balance.getId().getWalletId()),
                balance.getId().getAsset(),
                String.valueOf(balance.getAvailableSats()),
                String.valueOf(balance.getPendingSats()),
                String.valueOf(balance.getLockedSats()),
                String.valueOf(balance.getAutoHoldSats()),
                String.valueOf(balance.getObservedSats()),
                String.valueOf(balance.getNonce())));
    }
}
