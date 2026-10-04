package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.SettlementBalanceSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementSolvencySnapshot;
import java.util.List;

public interface PaymentGateSolvencyPort {
    boolean isEnabled();
    List<SettlementBalanceSnapshot> loadBalances();
    SettlementSolvencySnapshot computeSnapshot(long customerLiabilitiesSats, long systemProfitSats, long eligibleAssetsSats);
}
