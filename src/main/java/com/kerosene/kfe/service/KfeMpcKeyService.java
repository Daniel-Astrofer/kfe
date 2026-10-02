package com.kerosene.kfe.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;
import com.kerosene.common.financial.FinancialMpcKeyPort;

import java.util.UUID;

@Service
public class KfeMpcKeyService {

    private KfeMaintenanceGuard maintenanceGuard = KfeMaintenanceGuard.unavailable();

    @Autowired
    public void setMaintenanceGuard(KfeMaintenanceGuard maintenanceGuard) {
        this.maintenanceGuard = java.util.Objects.requireNonNull(maintenanceGuard);
    }

    private final FinancialMpcKeyPort mpcKeyPort;

    public KfeMpcKeyService(FinancialMpcKeyPort mpcKeyPort) {
        this.mpcKeyPort = mpcKeyPort;
    }

    public String keygenWallet(UUID walletId, Long userId) {
        return maintenanceGuard.executeMutation("mpc.keygen-wallet",
                () -> mpcKeyPort.keygenWallet(walletId, userId), ignored -> false);
    }
}
