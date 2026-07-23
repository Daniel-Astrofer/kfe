package com.kerosene.kfe.application.settlement;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Full AND-product of settlement flags plus quorum ack metadata when present.
 */
public record SettlementGateResult(
        List<FlagEvaluation> evaluations,
        int quorumAckCount,
        int quorumHealthyNodes) {

    public SettlementGateResult {
        evaluations = List.copyOf(evaluations);
    }

    public boolean passed() {
        return evaluations.stream().allMatch(FlagEvaluation::pass);
    }

    public List<SettlementFlag> failedFlags() {
        return evaluations.stream()
                .filter(evaluation -> !evaluation.pass())
                .map(FlagEvaluation::flag)
                .toList();
    }

    public Map<SettlementFlag, FlagEvaluation> byFlag() {
        Map<SettlementFlag, FlagEvaluation> map = new EnumMap<>(SettlementFlag.class);
        for (FlagEvaluation evaluation : evaluations) {
            map.put(evaluation.flag(), evaluation);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Compact audit payload: each flag → 0/1 and reason.
     */
    public Map<String, Object> toAuditPayload() {
        Map<String, Object> flags = new LinkedHashMap<>();
        for (FlagEvaluation evaluation : evaluations) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("value", evaluation.binary());
            entry.put("reason", evaluation.reason());
            flags.put(evaluation.flag().name(), entry);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("passed", passed() ? 1 : 0);
        payload.put("flags", flags);
        payload.put(
                "failedFlags",
                failedFlags().stream().map(Enum::name).collect(Collectors.toList()));
        payload.put("quorumAckCount", quorumAckCount);
        payload.put("quorumHealthyNodes", quorumHealthyNodes);
        return payload;
    }
}
