package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionOutcomePort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionPreparationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExternalExecutionPort;
import com.kerosene.kfe.paymentexecution.application.usecase.ProcessExecutionService;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeRailExecution;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Collection;

@Configuration
public class PaymentExecutionWorkerConfiguration {
    @Bean
    public ProcessExecutionService processExecutionService(
            ExecutionClaimPort claims, ExecutionPreparationPort preparation,
            List<ExternalExecutionPort> executors, List<KfeRailExecution> legacyExecutors,
            ExecutionOutcomePort outcomes) {
        List<ExternalExecutionPort> selected = executors.isEmpty()
                ? legacyExecutors.stream()
                        .map(com.kerosene.kfe.paymentexecution.adapters.out.execution.LegacyExternalExecutionAdapter::new)
                        .map(executor -> (ExternalExecutionPort) executor)
                        .toList()
                : List.copyOf(executors);
        return new ProcessExecutionService(claims, preparation, selected, outcomes);
    }

    /** Compatibility entry point for tests/consumers that still expose only KfeRailExecution. */
    public ProcessExecutionService processExecutionService(
            ExecutionClaimPort claims, ExecutionPreparationPort preparation,
            Collection<KfeRailExecution> legacyExecutors, ExecutionOutcomePort outcomes) {
        List<ExternalExecutionPort> bridges = legacyExecutors.stream()
                .map(com.kerosene.kfe.paymentexecution.adapters.out.execution.LegacyExternalExecutionAdapter::new)
                .map(executor -> (ExternalExecutionPort) executor)
                .toList();
        return new ProcessExecutionService(claims, preparation, bridges, outcomes);
    }
}
