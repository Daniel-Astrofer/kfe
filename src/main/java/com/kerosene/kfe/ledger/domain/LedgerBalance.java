package com.kerosene.kfe.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Framework-free balance aggregate. All arithmetic is checked and every mutation
 * returns an immutable transition describing the conservation delta.
 */
public final class LedgerBalance {
    private final UUID walletId;
    private final String asset;
    private long availableSats;
    private long pendingSats;
    private long lockedSats;
    private long autoHoldSats;
    private long observedSats;
    private long reorgDebtSats;
    private long version;

    public LedgerBalance(UUID walletId, String asset, long availableSats, long pendingSats,
            long lockedSats, long autoHoldSats, long observedSats, long reorgDebtSats, long version) {
        this.walletId = Objects.requireNonNull(walletId, "walletId is required");
        this.asset = requireAsset(asset);
        this.availableSats = nonNegative(availableSats, "availableSats");
        this.pendingSats = nonNegative(pendingSats, "pendingSats");
        this.lockedSats = nonNegative(lockedSats, "lockedSats");
        this.autoHoldSats = nonNegative(autoHoldSats, "autoHoldSats");
        this.observedSats = nonNegative(observedSats, "observedSats");
        this.reorgDebtSats = nonNegative(reorgDebtSats, "reorgDebtSats");
        this.version = nonNegative(version, "version");
    }

    public Transition reserve(long amountSats) {
        positive(amountSats);
        if (availableSats < amountSats) {
            throw new LedgerInvariantViolation("insufficient available balance");
        }
        long before = spendableTotal();
        availableSats = Math.subtractExact(availableSats, amountSats);
        lockedSats = Math.addExact(lockedSats, amountSats);
        bump();
        return transition(before, LedgerMovementType.RESERVE, amountSats, 0L,
                LedgerBucket.AVAILABLE, LedgerBucket.LOCKED);
    }

    public Transition releaseReserved(long amountSats) {
        positive(amountSats);
        if (lockedSats < amountSats) {
            throw new LedgerInvariantViolation("insufficient locked balance");
        }
        long before = spendableTotal();
        lockedSats = Math.subtractExact(lockedSats, amountSats);
        availableSats = Math.addExact(availableSats, amountSats);
        bump();
        return transition(before, LedgerMovementType.RELEASE_RESERVE, amountSats, 0L,
                LedgerBucket.LOCKED, LedgerBucket.AVAILABLE);
    }

    public Transition settleReservedDebit(long amountSats) {
        positive(amountSats);
        if (lockedSats < amountSats) {
            throw new LedgerInvariantViolation("insufficient locked balance");
        }
        long before = spendableTotal();
        lockedSats = Math.subtractExact(lockedSats, amountSats);
        bump();
        return transition(before, LedgerMovementType.SETTLE_DEBIT, amountSats, Math.negateExact(amountSats),
                LedgerBucket.LOCKED, null);
    }

    public Transition creditAvailable(long amountSats) {
        positive(amountSats);
        long before = spendableTotal();
        long debtPayment = Math.min(reorgDebtSats, amountSats);
        reorgDebtSats = Math.subtractExact(reorgDebtSats, debtPayment);
        availableSats = Math.addExact(availableSats, Math.subtractExact(amountSats, debtPayment));
        bump();
        return transition(before, debtPayment == amountSats
                ? LedgerMovementType.COMPENSATING_CREDIT : LedgerMovementType.CREDIT,
                amountSats, Math.subtractExact(amountSats, debtPayment), null, LedgerBucket.AVAILABLE);
    }

    /** Settlement credit is a liability transfer and must not be consumed by reorg debt. */
    public Transition creditSettlement(long amountSats) {
        positive(amountSats);
        long before = spendableTotal();
        availableSats = Math.addExact(availableSats, amountSats);
        bump();
        return transition(before, LedgerMovementType.CREDIT, amountSats, amountSats,
                null, LedgerBucket.AVAILABLE);
    }

    public long spendableTotal() {
        return Math.addExact(Math.addExact(availableSats, pendingSats),
                Math.addExact(lockedSats, autoHoldSats));
    }

    private Transition transition(long before, LedgerMovementType type, long amount, long expectedDelta,
            LedgerBucket from, LedgerBucket to) {
        long after = spendableTotal();
        if (Math.subtractExact(after, before) != expectedDelta) {
            throw new LedgerInvariantViolation("ledger conservation invariant violated");
        }
        return new Transition(type, amount, from, to, before, after, version);
    }

    private void bump() {
        version = Math.addExact(version, 1L);
    }

    private static String requireAsset(String asset) {
        if (asset == null || asset.isBlank() || asset.length() > 16) {
            throw new LedgerInvariantViolation("asset is required and must be at most 16 characters");
        }
        return asset;
    }

    private static long nonNegative(long value, String name) {
        if (value < 0L) {
            throw new LedgerInvariantViolation(name + " must be non-negative");
        }
        return value;
    }

    private static void positive(long value) {
        if (value <= 0L) {
            throw new LedgerInvariantViolation("amountSats must be positive");
        }
    }

    public UUID walletId() { return walletId; }
    public String asset() { return asset; }
    public long availableSats() { return availableSats; }
    public long pendingSats() { return pendingSats; }
    public long lockedSats() { return lockedSats; }
    public long autoHoldSats() { return autoHoldSats; }
    public long observedSats() { return observedSats; }
    public long reorgDebtSats() { return reorgDebtSats; }
    public long version() { return version; }

    public record Transition(LedgerMovementType movementType, long amountSats,
            LedgerBucket fromBucket, LedgerBucket toBucket, long spendableBeforeSats,
            long spendableAfterSats, long version) { }
}
