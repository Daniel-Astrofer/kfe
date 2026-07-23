package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.kerosene.common.vaultmesh.VaultMeshDayAdvanceResult;
import com.kerosene.common.vaultmesh.VaultMeshDayStatus;
import com.kerosene.common.vaultmesh.VaultMeshReshareResult;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * KFE requests daily vault-mesh day advance + reshare; vaults execute.
 *
 * <p>Idempotent: when mesh day_epoch is already UTC today, the tick is a no-op.
 * Vaults do not self-cron the calendar day — this worker is the orchestrator.
 */
@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
@ConditionalOnProperty(name = "kfe.vaultmesh.day-rotation.enabled", havingValue = "true")
public class KfeVaultMeshDayRotationWorker {

    private static final Logger log = LoggerFactory.getLogger(KfeVaultMeshDayRotationWorker.class);

    private final VaultMeshSettlementPort settlementPort;
    private final String voterId;
    private final boolean triggerReshare;
    private final String reshareReason;
    private final Clock clock;

    public KfeVaultMeshDayRotationWorker(
            VaultMeshSettlementPort settlementPort,
            @Value("${kfe.vaultmesh.day-rotation.voter-id:kfe}") String voterId,
            @Value("${kfe.vaultmesh.day-rotation.trigger-reshare:true}") boolean triggerReshare,
            @Value("${kfe.vaultmesh.day-rotation.reshare-reason:kfe-day-rotation}") String reshareReason) {
        this(settlementPort, voterId, triggerReshare, reshareReason, Clock.systemUTC());
    }

    /** Test helper with injectable clock. */
    KfeVaultMeshDayRotationWorker(
            VaultMeshSettlementPort settlementPort,
            String voterId,
            boolean triggerReshare,
            String reshareReason,
            Clock clock) {
        this.settlementPort = settlementPort;
        this.voterId = voterId == null || voterId.isBlank() ? "kfe" : voterId.trim();
        this.triggerReshare = triggerReshare;
        this.reshareReason =
                reshareReason == null || reshareReason.isBlank() ? "kfe-day-rotation" : reshareReason.trim();
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Cron schedule. Special value {@code -} disables (Spring). Prefer fixed-delay for lab polling.
     */
    @Scheduled(cron = "${kfe.vaultmesh.day-rotation.cron:-}")
    public void tickCron() {
        rotateIfNeeded();
    }

    /**
     * Fixed-delay poll. Set {@code fixed-delay-ms} to a positive value for lab; leave at default
     * when using cron-only (still polls every 15m unless overridden to a large value).
     */
    @Scheduled(
            fixedDelayString = "${kfe.vaultmesh.day-rotation.fixed-delay-ms:900000}",
            initialDelayString = "${kfe.vaultmesh.day-rotation.initial-delay-ms:60000}")
    public void tickFixedDelay() {
        rotateIfNeeded();
    }

    /**
     * @return outcome for tests / ops visibility
     */
    public Outcome rotateIfNeeded() {
        String utcToday = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
        VaultMeshDayStatus status = settlementPort.getDayStatus();
        if (status.error() != null && !status.stale()) {
            log.warn("[KFE VaultMesh Day] status failed: {}", status.error());
            return Outcome.failed(status.error());
        }
        if (status.upToDate() || (status.dayEpoch() != null && status.dayEpoch().compareTo(utcToday) >= 0)) {
            log.debug("[KFE VaultMesh Day] already current dayEpoch={} utcToday={}", status.dayEpoch(), utcToday);
            return Outcome.noop(status.dayEpoch() == null ? utcToday : status.dayEpoch());
        }

        String target = status.neededDayEpoch() == null || status.neededDayEpoch().isBlank()
                ? utcToday
                : status.neededDayEpoch().trim();
        log.info(
                "[KFE VaultMesh Day] advancing meshDay={} → target={} (voter={})",
                status.dayEpoch(),
                target,
                voterId);

        VaultMeshDayAdvanceResult vote = settlementPort.voteDay(voterId, target);
        if (!vote.ok()) {
            log.warn("[KFE VaultMesh Day] vote failed: {}", vote.error());
            return Outcome.failed(vote.error());
        }

        VaultMeshDayAdvanceResult advanced = settlementPort.advanceDay();
        if (!advanced.ok()) {
            log.warn("[KFE VaultMesh Day] advance failed: {}", advanced.error());
            return Outcome.failed(advanced.error());
        }

        if (triggerReshare) {
            VaultMeshReshareResult reshare = settlementPort.triggerReshare(reshareReason);
            if (!reshare.ok()) {
                // Daily policy may already have reshared on advance; treat conflict as soft.
                log.warn(
                        "[KFE VaultMesh Day] reshare trigger after advance: {} (dayEpoch={})",
                        reshare.error(),
                        advanced.dayEpoch());
                return Outcome.advanced(advanced.dayEpoch(), false, reshare.error());
            }
            log.info(
                    "[KFE VaultMesh Day] advanced+reshared dayEpoch={} policy={}",
                    advanced.dayEpoch(),
                    reshare.policy());
            return Outcome.advanced(advanced.dayEpoch(), true, null);
        }

        log.info("[KFE VaultMesh Day] advanced dayEpoch={} (reshare trigger skipped)", advanced.dayEpoch());
        return Outcome.advanced(advanced.dayEpoch(), false, null);
    }

    public record Outcome(Kind kind, String dayEpoch, boolean reshared, String error) {
        public enum Kind {
            NOOP,
            ADVANCED,
            FAILED
        }

        static Outcome noop(String dayEpoch) {
            return new Outcome(Kind.NOOP, dayEpoch, false, null);
        }

        static Outcome advanced(String dayEpoch, boolean reshared, String error) {
            return new Outcome(Kind.ADVANCED, dayEpoch, reshared, error);
        }

        static Outcome failed(String error) {
            return new Outcome(Kind.FAILED, null, false, error);
        }
    }
}
