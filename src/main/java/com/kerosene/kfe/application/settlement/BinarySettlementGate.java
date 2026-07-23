package com.kerosene.kfe.application.settlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.model.KfeBalanceEntity;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.service.KfeBalanceService;
import com.kerosene.kfe.service.KfeLightningJammingGuard;
import com.kerosene.kfe.service.KfeLightningLiquidityService;
import com.kerosene.kfe.service.KfeCapacitySignalStore;
import com.kerosene.kfe.service.KfeLightningOpsMetrics;
import com.kerosene.kfe.service.KfeQuorumGateway;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Strict binary AND gate for KFE liquidation (architecture doc §2).
 *
 * <p>Does not mutate balances. Acquires a row lock when checking available balance so the
 * subsequent reserve in the same DB transaction is race-safe (V_LOCK_BANDO + V_SALDO_DISP).
 *
 * <p>Lightning-specific liquidity / jamming / circuit-breaker flags respect
 * {@code kfe.settlement.lightning.risk-gate-mode}:
 * <ul>
 *   <li>{@code beta-pass} (default) — pass with forensic reason {@code BETA_LIMITED:...}</li>
 *   <li>{@code enforce} — fail closed until liquidity reservation is implemented</li>
 * </ul>
 */
@Service
public class BinarySettlementGate {

    private static final String ASSET_BTC = "BTC";
    private static final long MAX_SATOSHIS = 2_100_000_000_000_000L;

    private final KfeBalanceService balanceService;
    private final KfeQuorumGateway quorumGateway;
    private final KfeAuditLogService auditLogService;
    private final KfeLightningLiquidityService lightningLiquidityService;
    private final KfeLightningJammingGuard lightningJammingGuard;
    private final ObjectProvider<KfeLightningOpsMetrics> opsMetrics;
    private final ObjectProvider<KfeCapacitySignalStore> capacitySignalStore;
    private final Environment environment;
    private final String lightningRiskGateMode;
    private final boolean porGateEnabled;
    private final boolean allowSimulatedBalances;

    public BinarySettlementGate(
            KfeBalanceService balanceService,
            KfeQuorumGateway quorumGateway,
            KfeAuditLogService auditLogService,
            KfeLightningLiquidityService lightningLiquidityService,
            KfeLightningJammingGuard lightningJammingGuard,
            ObjectProvider<KfeLightningOpsMetrics> opsMetrics,
            ObjectProvider<KfeCapacitySignalStore> capacitySignalStore,
            Environment environment,
            @Value("${kfe.settlement.lightning.risk-gate-mode:beta-pass}") String lightningRiskGateMode,
            @Value("${kfe.settlement.por-gate-enabled:false}") boolean porGateEnabled,
            @Value("${kfe.settlement.allow-simulated-balances:false}") boolean allowSimulatedBalances) {
        this.balanceService = balanceService;
        this.quorumGateway = quorumGateway;
        this.auditLogService = auditLogService;
        this.lightningLiquidityService = lightningLiquidityService;
        this.lightningJammingGuard = lightningJammingGuard;
        this.opsMetrics = opsMetrics;
        this.capacitySignalStore = capacitySignalStore;
        this.environment = environment;
        this.lightningRiskGateMode = normalizeMode(lightningRiskGateMode);
        this.porGateEnabled = porGateEnabled;
        this.allowSimulatedBalances = allowSimulatedBalances;
    }

    /**
     * Evaluate all flags, call quorum (MPC), audit forensically (survives rollback on fail).
     *
     * @throws SettlementGateRejectedException when any flag is 0
     */
    public SettlementGateResult evaluateAndRequirePass(SettlementGateCommand command) {
        SettlementGateResult result = evaluate(command);
        // Join the outer submit TX for gate audit. Using recordInNewTransaction here deadlocks:
        // INTENT/VALIDATING already took pg_advisory_xact_lock(GLOBAL_AUDIT_APPENDER) on this
        // connection, so a nested TX waiting on the same lock never completes (clients spin on
        // "em andamento"). Fail forensic that must outlive rollback is scheduled after outer
        // completion when needed — see persistGateAuditAfterOuterRelease.
        persistGateAuditInCallerTransaction(command.transactionId(), command.sourceWalletId(), result);
        KfeLightningOpsMetrics metrics = opsMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.recordSettlementGate(result.passed() ? "pass" : "fail");
        }
        if (!result.passed()) {
            recordLiquidityStress(result);
            throw new SettlementGateRejectedException(result);
        }
        return result;
    }

    private void recordLiquidityStress(SettlementGateResult result) {
        if (result == null || result.byFlag() == null) {
            return;
        }
        FlagEvaluation liquidez = result.byFlag().get(SettlementFlag.V_LIQUIDEZ);
        if (liquidez == null || liquidez.pass()) {
            return;
        }
        KfeCapacitySignalStore signals = capacitySignalStore.getIfAvailable();
        if (signals != null) {
            signals.recordLiquidityReject();
        }
        KfeLightningOpsMetrics metrics = opsMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.recordLiquidityReject(
                    liquidez.reason() != null ? liquidez.reason() : "V_LIQUIDEZ");
        }
    }

    public SettlementGateResult evaluate(SettlementGateCommand command) {
        List<FlagEvaluation> evaluations = new ArrayList<>();
        int quorumAck = 0;
        int quorumHealthy = 0;

        evaluations.add(evaluateIdempotencia(command));

        LockSaldoOutcome lockSaldo = evaluateLockSaldo(command);
        evaluations.add(lockSaldo.lockFlag());
        evaluations.add(evaluateAtomicidade(command));
        evaluations.add(lockSaldo.saldoFlag());
        evaluations.add(evaluateDinheiroReal());
        evaluations.add(evaluateLiquidez(command));
        evaluations.add(evaluateP2p());

        MpcOutcome mpc = evaluateMpc(command);
        quorumAck = mpc.ackCount();
        quorumHealthy = mpc.healthyNodes();
        evaluations.add(mpc.flag());

        evaluations.add(evaluateReservaMat(command, lockSaldo));
        evaluations.add(evaluateNoJamming(command));
        evaluations.add(evaluateCircuitBreaker(command));

        return new SettlementGateResult(orderFlags(evaluations), quorumAck, quorumHealthy);
    }

    private FlagEvaluation evaluateIdempotencia(SettlementGateCommand command) {
        if (!command.idempotencyReserved()) {
            return FlagEvaluation.fail(SettlementFlag.V_IDEMPOTENCIA, "IDEMPOTENCY_NOT_RESERVED");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            return FlagEvaluation.fail(SettlementFlag.V_IDEMPOTENCIA, "IDEMPOTENCY_KEY_MISSING");
        }
        return FlagEvaluation.pass(SettlementFlag.V_IDEMPOTENCIA, "IDEMPOTENCY_RESERVED_DB");
    }

    private FlagEvaluation evaluateAtomicidade(SettlementGateCommand command) {
        if (command.amountSats() <= 0L) {
            return FlagEvaluation.fail(SettlementFlag.V_ATOMICIDADE, "AMOUNT_NOT_POSITIVE");
        }
        if (command.networkFeeSats() < 0L) {
            return FlagEvaluation.fail(SettlementFlag.V_ATOMICIDADE, "FEE_NEGATIVE");
        }
        if (command.totalDebitSats() <= 0L) {
            return FlagEvaluation.fail(SettlementFlag.V_ATOMICIDADE, "TOTAL_DEBIT_NOT_POSITIVE");
        }
        if (command.amountSats() > MAX_SATOSHIS
                || command.networkFeeSats() > MAX_SATOSHIS
                || command.totalDebitSats() > MAX_SATOSHIS) {
            return FlagEvaluation.fail(SettlementFlag.V_ATOMICIDADE, "EXCEEDS_MAX_SATS");
        }
        try {
            Math.addExact(command.amountSats(), command.networkFeeSats());
        } catch (ArithmeticException ex) {
            return FlagEvaluation.fail(SettlementFlag.V_ATOMICIDADE, "SAT_OVERFLOW");
        }
        return FlagEvaluation.pass(SettlementFlag.V_ATOMICIDADE, "INTEGER_SATS_OK");
    }

    private LockSaldoOutcome evaluateLockSaldo(SettlementGateCommand command) {
        if (!command.requiresSourceReserve()) {
            return new LockSaldoOutcome(
                    FlagEvaluation.pass(SettlementFlag.V_LOCK_BANDO, "LOCK_NOT_REQUIRED"),
                    FlagEvaluation.pass(SettlementFlag.V_SALDO_DISP, "RESERVE_NOT_REQUIRED"),
                    null);
        }
        if (command.sourceWalletId() == null) {
            return new LockSaldoOutcome(
                    FlagEvaluation.fail(SettlementFlag.V_LOCK_BANDO, "SOURCE_WALLET_MISSING"),
                    FlagEvaluation.fail(SettlementFlag.V_SALDO_DISP, "SOURCE_WALLET_MISSING"),
                    null);
        }
        try {
            KfeBalanceEntity balance =
                    balanceService.requireForUpdate(command.sourceWalletId(), ASSET_BTC);
            if (balance.getAvailableSats() < command.totalDebitSats()) {
                return new LockSaldoOutcome(
                        FlagEvaluation.pass(SettlementFlag.V_LOCK_BANDO, "ROW_LOCK_ACQUIRED"),
                        FlagEvaluation.fail(SettlementFlag.V_SALDO_DISP, "INSUFFICIENT_AVAILABLE"),
                        balance);
            }
            return new LockSaldoOutcome(
                    FlagEvaluation.pass(SettlementFlag.V_LOCK_BANDO, "ROW_LOCK_ACQUIRED"),
                    FlagEvaluation.pass(SettlementFlag.V_SALDO_DISP, "AVAILABLE_COVERS_TOTAL_DEBIT"),
                    balance);
        } catch (RuntimeException ex) {
            String reason = safeReason(ex);
            return new LockSaldoOutcome(
                    FlagEvaluation.fail(SettlementFlag.V_LOCK_BANDO, "ROW_LOCK_FAILED:" + reason),
                    FlagEvaluation.fail(SettlementFlag.V_SALDO_DISP, "BALANCE_UNAVAILABLE:" + reason),
                    null);
        }
    }

    private FlagEvaluation evaluateDinheiroReal() {
        boolean production = isProductionProfile();
        if (production && allowSimulatedBalances) {
            return FlagEvaluation.fail(
                    SettlementFlag.V_DINHEIRO_REAL,
                    "SIMULATED_BALANCES_FORBIDDEN_IN_PROD");
        }
        if (production) {
            return FlagEvaluation.pass(SettlementFlag.V_DINHEIRO_REAL, "PRODUCTION_NO_SIMULATION");
        }
        return FlagEvaluation.pass(SettlementFlag.V_DINHEIRO_REAL, "NON_PRODUCTION_OK");
    }

    private FlagEvaluation evaluateLiquidez(SettlementGateCommand command) {
        if (!isLightningOutbound(command)) {
            return FlagEvaluation.pass(SettlementFlag.V_LIQUIDEZ, "NOT_APPLICABLE");
        }
        if (!lightningLiquidityService.isLive()) {
            return lightningRiskFlag(SettlementFlag.V_LIQUIDEZ, "LIGHTNING_GATEWAY_NOT_LIVE");
        }
        long free = lightningLiquidityService.freeOutboundCapacitySats();
        if (free < 0L) {
            return lightningRiskFlag(SettlementFlag.V_LIQUIDEZ, "OUTBOUND_CAPACITY_UNAVAILABLE");
        }
        if (!lightningLiquidityService.canCoverOutbound(command.totalDebitSats())) {
            return FlagEvaluation.fail(
                    SettlementFlag.V_LIQUIDEZ,
                    "INSUFFICIENT_FREE_OUTBOUND_CAPACITY:" + free);
        }
        return FlagEvaluation.pass(SettlementFlag.V_LIQUIDEZ, "FREE_OUTBOUND_CAPACITY_OK:" + free);
    }

    private FlagEvaluation evaluateP2p() {
        return FlagEvaluation.pass(SettlementFlag.V_P2P, "NOT_APPLICABLE");
    }

    private MpcOutcome evaluateMpc(SettlementGateCommand command) {
        if (command.proposalHash() == null || command.proposalHash().isBlank()) {
            return new MpcOutcome(
                    FlagEvaluation.fail(SettlementFlag.V_ASSINATURA_MPC, "MISSING_PROPOSAL_HASH"),
                    0,
                    0);
        }
        try {
            KfeQuorumGateway.Result quorum =
                    quorumGateway.requireHealthyUnanimousConsensus(command.proposalHash());
            if (quorum.acceptedNodes() > 0
                    && quorum.acceptedNodes() == quorum.totalHealthyNodes()) {
                return new MpcOutcome(
                        FlagEvaluation.pass(SettlementFlag.V_ASSINATURA_MPC, "QUORUM_UNANIMOUS"),
                        quorum.acceptedNodes(),
                        quorum.totalHealthyNodes());
            }
            return new MpcOutcome(
                    FlagEvaluation.fail(SettlementFlag.V_ASSINATURA_MPC, "QUORUM_NOT_UNANIMOUS"),
                    quorum.acceptedNodes(),
                    quorum.totalHealthyNodes());
        } catch (RuntimeException ex) {
            return new MpcOutcome(
                    FlagEvaluation.fail(
                            SettlementFlag.V_ASSINATURA_MPC,
                            "QUORUM_REJECTED:" + safeReason(ex)),
                    0,
                    0);
        }
    }

    private FlagEvaluation evaluateReservaMat(SettlementGateCommand command, LockSaldoOutcome lockSaldo) {
        if (!porGateEnabled) {
            return FlagEvaluation.pass(SettlementFlag.V_RESERVA_MAT, "POR_GATE_NOT_ENFORCED");
        }
        if (!command.requiresSourceReserve()) {
            return FlagEvaluation.pass(SettlementFlag.V_RESERVA_MAT, "NO_EXPOSURE_CHANGE");
        }
        if (lockSaldo.balance() == null) {
            return FlagEvaluation.fail(SettlementFlag.V_RESERVA_MAT, "BALANCE_UNAVAILABLE");
        }
        long availableAfter = lockSaldo.balance().getAvailableSats() - command.totalDebitSats();
        if (availableAfter < 0L) {
            return FlagEvaluation.fail(SettlementFlag.V_RESERVA_MAT, "NEGATIVE_AVAILABLE_AFTER");
        }
        return FlagEvaluation.pass(SettlementFlag.V_RESERVA_MAT, "LOCAL_RESERVE_INVARIANT_OK");
    }

    private FlagEvaluation evaluateNoJamming(SettlementGateCommand command) {
        if (!isLightningOutbound(command)) {
            return FlagEvaluation.pass(SettlementFlag.V_NO_JAMMING, "NOT_APPLICABLE");
        }
        KfeLightningJammingGuard.JammingCheck check = lightningJammingGuard.evaluate();
        if (check.allowed()) {
            return FlagEvaluation.pass(SettlementFlag.V_NO_JAMMING, check.reason());
        }
        if ("enforce".equals(lightningRiskGateMode) || check.hardBlock()) {
            return FlagEvaluation.fail(SettlementFlag.V_NO_JAMMING, check.reason());
        }
        return FlagEvaluation.pass(SettlementFlag.V_NO_JAMMING, "BETA_LIMITED:" + check.reason());
    }

    private FlagEvaluation evaluateCircuitBreaker(SettlementGateCommand command) {
        if (!isLightningOutbound(command)) {
            return FlagEvaluation.pass(SettlementFlag.V_CIRCUIT_BREAKER, "NOT_APPLICABLE");
        }
        if (lightningLiquidityService.circuitBreakerOpen()) {
            return FlagEvaluation.fail(
                    SettlementFlag.V_CIRCUIT_BREAKER,
                    "OUTBOUND_BELOW_CIRCUIT_FLOOR");
        }
        if (!lightningLiquidityService.isLive()) {
            return lightningRiskFlag(
                    SettlementFlag.V_CIRCUIT_BREAKER, "LIGHTNING_GATEWAY_NOT_LIVE");
        }
        return FlagEvaluation.pass(SettlementFlag.V_CIRCUIT_BREAKER, "CIRCUIT_CLOSED");
    }

    private FlagEvaluation lightningRiskFlag(SettlementFlag flag, String missingReason) {
        if ("enforce".equals(lightningRiskGateMode)) {
            return FlagEvaluation.fail(flag, missingReason);
        }
        return FlagEvaluation.pass(flag, "BETA_LIMITED:" + missingReason);
    }

    private boolean isLightningOutbound(SettlementGateCommand command) {
        return command.rail() == KfeRail.LIGHTNING && command.direction() == KfeDirection.OUTBOUND;
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "beta-pass";
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        if ("enforce".equals(normalized)) {
            return "enforce";
        }
        return "beta-pass";
    }

    private static String safeReason(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    private static List<FlagEvaluation> orderFlags(List<FlagEvaluation> evaluations) {
        List<FlagEvaluation> ordered = new ArrayList<>();
        for (SettlementFlag flag : SettlementFlag.values()) {
            evaluations.stream()
                    .filter(evaluation -> evaluation.flag() == flag)
                    .findFirst()
                    .ifPresent(ordered::add);
        }
        return ordered;
    }

    /**
     * Gate audit in the caller's transaction (safe with the shared audit appender xact lock).
     *
     * <p>On gate failure the outer submit rolls back, so this row is lost with it. That is
     * preferred to deadlocking the payment request; metrics + application logs still capture
     * the reject reason.
     */
    public void persistGateAuditInCallerTransaction(
            java.util.UUID transactionId, java.util.UUID walletId, SettlementGateResult result) {
        KfeTransactionStatus toStatus =
                result.passed() ? KfeTransactionStatus.QUORUM_SYNC : KfeTransactionStatus.FAILED;
        auditLogService.record(
                "KFE_SETTLEMENT_GATE",
                transactionId,
                walletId,
                KfeTransactionStatus.VALIDATING,
                toStatus,
                result.toAuditPayload());
    }

    /**
     * @deprecated Use {@link #persistGateAuditInCallerTransaction}; kept for tests that still
     *     spy on the old name.
     */
    @Deprecated
    public void persistGateAudit(
            java.util.UUID transactionId, java.util.UUID walletId, SettlementGateResult result) {
        persistGateAuditInCallerTransaction(transactionId, walletId, result);
    }

    private record LockSaldoOutcome(
            FlagEvaluation lockFlag,
            FlagEvaluation saldoFlag,
            KfeBalanceEntity balance) {
    }

    private record MpcOutcome(FlagEvaluation flag, int ackCount, int healthyNodes) {
    }
}
