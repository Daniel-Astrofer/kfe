package com.kerosene.kfe.service;

import org.springframework.stereotype.Service;
import com.kerosene.common.financial.FinancialQuorumPort;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class KfeQuorumGateway {

    private final FinancialQuorumPort quorumPort;
    private KfeMaintenanceGuard maintenanceGuard = KfeMaintenanceGuard.unavailable();

    @Autowired
    public void setMaintenanceGuard(KfeMaintenanceGuard guard) {
        this.maintenanceGuard = java.util.Objects.requireNonNull(guard);
    }

    public KfeQuorumGateway(FinancialQuorumPort quorumPort) {
        this.quorumPort = quorumPort;
    }

    public Result requireHealthyUnanimousConsensus(String proposalHash) {
        return maintenanceGuard.executeMutation("quorum.consensus", () -> {
            FinancialQuorumPort.Result result = quorumPort.requireHealthyUnanimousConsensus(proposalHash);
            return new Result(result.acceptedNodes(), result.totalHealthyNodes());
        }, ignored -> false);
    }

    public record Result(int acceptedNodes, int totalHealthyNodes) {
    }
}
