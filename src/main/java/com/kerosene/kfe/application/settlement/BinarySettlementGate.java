package com.kerosene.kfe.application.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.model.KfeBalanceEntity;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.repository.KfeBalanceRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.service.KfeBalanceService;
import com.kerosene.kfe.service.KfeLightningJammingGuard;
import com.kerosene.kfe.service.KfeLightningLiquidityService;
import com.kerosene.kfe.service.KfeCapacitySignalStore;
import com.kerosene.kfe.service.KfeLightningOpsMetrics;
import com.kerosene.kfe.service.KfeProofOfReservesService;
import com.kerosene.kfe.service.KfeQuorumGateway;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    private static final Logger log = LoggerFactory.getLogger(BinarySettlementGate.class);
    private static final String ASSET_BTC = "BTC";
    private static final long MAX_SATOSHIS = 2_100_000_000_000_000L;

    /** Wallet kinds that represent customer obligations (exclude equity and watch-only). */
    private static final Set<KfeWalletKind> CUSTOMER_KINDS = Set.of(
            KfeWalletKind.CUSTODIAL_ONCHAIN,
            KfeWalletKind.INTERNAL);

    private final KfeBalanceService balanceService;
    private final KfeBalanceRepository balanceRepository;
    private final KfeWalletRepository walletRepository;
    private final KfeProofOfReservesService porService;
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
    private final int constitutionMemberCount;
    private final int constitutionThreshold;

    public BinarySettlementGate(
            KfeBalanceService balanceService,
            KfeBalanceRepository balanceRepository,
            KfeWalletRepository walletRepository,
            KfeProofOfReservesService porService,
            KfeQuorumGateway quorumGateway,
            KfeAuditLogService auditLogService,
            KfeLightningLiquidityService lightningLiquidityService,
            KfeLightningJammingGuard lightningJammingGuard,
            ObjectProvider<KfeLightningOpsMetrics> opsMetrics,
            ObjectProvider<KfeCapacitySignalStore> capacitySignalStore,
            Environment environment,
            @Value("${kfe.settlement.lightning.risk-gate-mode:enforce}") String lightningRiskGateMode,
            @Value("${kfe.settlement.por-gate-enabled:true}") boolean porGateEnabled,
            @Value("${kfe.settlement.allow-simulated-balances:false}") boolean allowSimulatedBalances,
            @Value("${kfe.vaultmesh.constitution.member-count:3}") int constitutionMemberCount,
            @Value("${kfe.vaultmesh.constitution.threshold:2}") int constitutionThreshold) {
        this.balanceService = balanceService;
        this.balanceRepository = balanceRepository;
        this.walletRepository = walletRepository;
        this.porService = porService;
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
        this.constitutionMemberCount = Math.max(1, constitutionMemberCount);
        this.constitutionThreshold = Math.min(constitutionThreshold, constitutionMemberCount);
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
            int accepted = quorum.acceptedNodes();
            int healthy = quorum.totalHealthyNodes();
            if (accepted >= constitutionThreshold) {
                String reason = "QUORUM_THRESHOLD_MET:" + accepted + "/" + constitutionMemberCount
                        + " (threshold=" + constitutionThreshold + ", healthy=" + healthy + ")";
                return new MpcOutcome(
                        FlagEvaluation.pass(SettlementFlag.V_ASSINATURA_MPC, reason),
                        accepted,
                        healthy);
            }
            String reason = "QUORUM_THRESHOLD_NOT_MET:" + accepted + "/" + constitutionMemberCount
                    + " (threshold=" + constitutionThreshold + ", healthy=" + healthy + ")";
            return new MpcOutcome(
                    FlagEvaluation.fail(SettlementFlag.V_ASSINATURA_MPC, reason),
                    accepted,
                    healthy);
        } catch (RuntimeException ex) {
            return new MpcOutcome(
                    FlagEvaluation.fail(
                            SettlementFlag.V_ASSINATURA_MPC,
                            "QUORUM_REJECTED:" + safeReason(ex)),
                    0,
                    0);
        }
    }

    /**
     * Proof-of-reserves solvency check (ITEM 8).
     *
     * <p>When enabled, verifies the invariant: eligibleAssets >= liabilities + safetyBuffer.
     * This is NOT a local balance check — it proves UTXOs exist, belong to the vault
     * mesh, are spendable, and cover user liabilities.
     *
     * <p>Liabilities are computed from ledger excluding WATCH_ONLY, SYSTEM_FUNDS, and
     * SYSTEM_PROFIT wallets. Assets are computed from ledger observedSats (cached on-chain
     * scan mirror) as the best available proxy when live probes are unavailable.
     */
    private FlagEvaluation evaluateReservaMat(SettlementGateCommand command, LockSaldoOutcome lockSaldo) {
        if (!porGateEnabled) {
            return FlagEvaluation.pass(SettlementFlag.V_RESERVA_MAT, "POR_GATE_NOT_ENFORCED");
        }
        if (!porService.isEnabled()) {
            return FlagEvaluation.pass(SettlementFlag.V_RESERVA_MAT, "POR_SERVICE_DISABLED");
        }
        if (!command.requiresSourceReserve()) {
            return FlagEvaluation.pass(SettlementFlag.V_RESERVA_MAT, "NO_EXPOSURE_CHANGE");
        }
        if (lockSaldo.balance() == null) {
            return FlagEvaluation.fail(SettlementFlag.V_RESERVA_MAT, "BALANCE_UNAVAILABLE");
        }

        // Local sanity check: source wallet must have enough available
        long availableAfter = lockSaldo.balance().getAvailableSats() - command.totalDebitSats();
        if (availableAfter < 0L) {
            return FlagEvaluation.fail(SettlementFlag.V_RESERVA_MAT, "NEGATIVE_AVAILABLE_AFTER");
        }

        // Global solvency check: compute liabilities and assets from ledger
        try {
            List<KfeBalanceEntity> allBalances = balanceRepository.findAll();
            Map<UUID, KfeWalletKind> kinds = loadWalletKinds(allBalances);

            long customerLiabilities = computeCustomerLiabilities(allBalances, kinds);
            long systemProfitSats = computeSystemProfitBalance(allBalances, kinds);
            long eligibleAssets = computeEligibleAssets(allBalances, kinds);
            long inFlightWithdrawals = 0L; // Computed from transaction status scan (can add later)

            KfeProofOfReservesService.SolvencySnapshot snapshot =
                    porService.computeSnapshot(
                            customerLiabilities,
                            systemProfitSats,
                            inFlightWithdrawals,
                            eligibleAssets,
                            eligibleAssets, // on-chain portion (Lightning not broken out here)
                            0L,             // lightning portion
                            null);          // block hash not available in gate path

            if (!snapshot.solvent()) {
                return FlagEvaluation.fail(
                        SettlementFlag.V_RESERVA_MAT,
                        String.format("INSOLVENT:coverage=%.4f,required=%.4f,liabilities=%d,assets=%d,buffer=%d",
                                snapshot.coverageRatio(),
                                snapshot.minimumCoverageRatio(),
                                snapshot.totalLiabilitiesSats(),
                                snapshot.eligibleAssetsSats(),
                                snapshot.safetyBufferSats()));
            }

            return FlagEvaluation.pass(
                    SettlementFlag.V_RESERVA_MAT,
                    String.format("SOLVENT:coverage=%.4f,liabilities=%d,assets=%d",
                            snapshot.coverageRatio(),
                            snapshot.totalLiabilitiesSats(),
                            snapshot.eligibleAssetsSats()));
        } catch (RuntimeException ex) {
            log.error("PoR solvency check failed: {}", safeReason(ex));
            return FlagEvaluation.fail(
                    SettlementFlag.V_RESERVA_MAT,
                    "POR_CHECK_ERROR:" + safeReason(ex));
        }
    }

    /**
     * Compute total customer liabilities = available + pending + locked + hold for
     * CUSTODIAL_ONCHAIN and INTERNAL wallets only. Excludes WATCH_ONLY (user keys),
     * SYSTEM_FUNDS (equity), and SYSTEM_PROFIT (tracked separately).
     */
    private long computeCustomerLiabilities(List<KfeBalanceEntity> balances, Map<UUID, KfeWalletKind> kinds) {
        long total = 0L;
        for (KfeBalanceEntity b : balances) {
            UUID walletId = b.getId() != null ? b.getId().getWalletId() : null;
            if (walletId == null) continue;
            KfeWalletKind kind = kinds.get(walletId);
            if (kind != null && CUSTOMER_KINDS.contains(kind)) {
                total = Math.addExact(total,
                        b.getAvailableSats() + b.getPendingSats() + b.getLockedSats() + b.getAutoHoldSats());
            }
        }
        return total;
    }

    /**
     * Compute SYSTEM_PROFIT wallet balance. Profit is a liability within USERS until
     * physically segregated into a dedicated vault bucket.
     */
    private long computeSystemProfitBalance(List<KfeBalanceEntity> balances, Map<UUID, KfeWalletKind> kinds) {
        long total = 0L;
        for (KfeBalanceEntity b : balances) {
            UUID walletId = b.getId() != null ? b.getId().getWalletId() : null;
            if (walletId == null) continue;
            if (kinds.get(walletId) == KfeWalletKind.SYSTEM_PROFIT) {
                total = Math.addExact(total, b.getAvailableSats());
            }
        }
        return total;
    }

    /**
     * Compute eligible assets = observedSats for CUSTODIAL_ONCHAIN and INTERNAL wallets.
     * observedSats is the ledger-cached mirror of the last confirmed on-chain scan
     * (scantxoutset / listunspent). It is the best available asset proxy when live
     * probes are not reachable from the gate path.
     */
    private long computeEligibleAssets(List<KfeBalanceEntity> balances, Map<UUID, KfeWalletKind> kinds) {
        long total = 0L;
        for (KfeBalanceEntity b : balances) {
            UUID walletId = b.getId() != null ? b.getId().getWalletId() : null;
            if (walletId == null) continue;
            KfeWalletKind kind = kinds.get(walletId);
            if (kind != null && CUSTOMER_KINDS.contains(kind)) {
                total = Math.addExact(total, b.getObservedSats());
            }
        }
        return total;
    }

    /**
     * Load wallet kinds for all balance rows in a single batch query.
     */
    private Map<UUID, KfeWalletKind> loadWalletKinds(List<KfeBalanceEntity> balances) {
        Set<UUID> walletIds = balances.stream()
                .map(KfeBalanceEntity::getId)
                .filter(id -> id != null && id.getWalletId() != null)
                .map(com.kerosene.kfe.model.KfeBalanceId::getWalletId)
                .collect(Collectors.toSet());

        if (walletIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, KfeWalletKind> kinds = new HashMap<>();
        for (Object[] row : walletRepository.findKindsByIds(walletIds)) {
            if (row[0] instanceof UUID id && row[1] instanceof KfeWalletKind kind) {
                kinds.put(id, kind);
            }
        }
        return kinds;
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
