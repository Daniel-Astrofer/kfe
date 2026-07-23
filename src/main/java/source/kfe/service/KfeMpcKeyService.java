package source.kfe.service;

import org.springframework.stereotype.Service;
import source.common.financial.FinancialMpcKeyPort;

import java.util.UUID;

@Service
public class KfeMpcKeyService {

    private final FinancialMpcKeyPort mpcKeyPort;

    public KfeMpcKeyService(FinancialMpcKeyPort mpcKeyPort) {
        this.mpcKeyPort = mpcKeyPort;
    }

    public String keygenWallet(UUID walletId, Long userId) {
        return mpcKeyPort.keygenWallet(walletId, userId);
    }
}
