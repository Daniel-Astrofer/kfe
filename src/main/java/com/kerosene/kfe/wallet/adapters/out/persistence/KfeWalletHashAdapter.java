package com.kerosene.kfe.wallet.adapters.out.persistence;

import org.springframework.stereotype.Component;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.wallet.application.port.out.WalletHashPort;

@Component
public final class KfeWalletHashAdapter implements WalletHashPort {
    private final KfeHashService hash;

    public KfeWalletHashAdapter(KfeHashService hash) {
        this.hash = hash;
    }

    @Override
    public String sha256(String value) {
        return hash.sha256(value);
    }
}
