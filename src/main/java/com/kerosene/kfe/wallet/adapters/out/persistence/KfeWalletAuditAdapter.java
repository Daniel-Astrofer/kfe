package com.kerosene.kfe.wallet.adapters.out.persistence;

import org.springframework.stereotype.Component;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.wallet.application.port.out.WalletAuditPort;

import java.util.Map;
import java.util.UUID;

@Component
public final class KfeWalletAuditAdapter implements WalletAuditPort {
    private final KfeAuditLogService audit;

    public KfeWalletAuditAdapter(KfeAuditLogService audit) {
        this.audit = audit;
    }

    @Override
    public void record(String eventType, UUID walletId, Map<String, String> payload) {
        audit.record(eventType, null, walletId, null, null, payload);
    }
}
