package com.kerosene.kfe.service;

import org.springframework.stereotype.Service;
import com.kerosene.common.financial.FinancialQuorumPort;

@Service
public class KfeQuorumGateway {

    private final FinancialQuorumPort quorumPort;

    public KfeQuorumGateway(FinancialQuorumPort quorumPort) {
        this.quorumPort = quorumPort;
    }

    public Result requireHealthyUnanimousConsensus(String proposalHash) {
        FinancialQuorumPort.Result result = quorumPort.requireHealthyUnanimousConsensus(proposalHash);
        return new Result(result.acceptedNodes(), result.totalHealthyNodes());
    }

    public record Result(int acceptedNodes, int totalHealthyNodes) {
    }
}
