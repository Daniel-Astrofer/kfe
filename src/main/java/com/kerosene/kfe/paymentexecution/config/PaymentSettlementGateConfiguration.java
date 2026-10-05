package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentSettlementGateService;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementGatePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentSettlementGateConfiguration {
    @Bean
    SettlementGatePolicy settlementGatePolicy(
            @Value("${kfe.settlement.lightning.risk-gate-mode:enforce}") String lightningRiskGateMode,
            @Value("${kfe.settlement.por-gate-enabled:true}") boolean porGateEnabled,
            @Value("${kfe.vaultmesh.constitution.member-count:3}") int constitutionMemberCount,
            @Value("${kfe.vaultmesh.constitution.threshold:2}") int constitutionThreshold) {
        return new SettlementGatePolicy(lightningRiskGateMode, porGateEnabled, false,
                constitutionMemberCount, constitutionThreshold);
    }

    @Bean
    PaymentSettlementGateService paymentSettlementGateService(PaymentGateBalancePort balances,
            PaymentGateSolvencyPort solvency, PaymentGateQuorumPort quorum, PaymentGateLightningPort lightning,
            PaymentGateEnvironmentPort environment, PaymentGateAuditPort audit, PaymentGateTelemetryPort telemetry,
            SettlementGatePolicy policy) {
        return new PaymentSettlementGateService(balances, solvency, quorum, lightning, environment, audit, telemetry, policy);
    }
}
