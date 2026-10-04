package com.kerosene.kfe.ledger.application.service;

import com.kerosene.kfe.ledger.application.port.out.LedgerAccountPort;
import com.kerosene.kfe.ledger.application.port.out.LedgerPostingPort;
import com.kerosene.kfe.ledger.application.port.out.LedgerMutationObserver;
import com.kerosene.kfe.ledger.domain.LedgerBalance;
import com.kerosene.kfe.ledger.domain.LedgerInvariantViolation;
import com.kerosene.kfe.ledger.domain.LedgerMovementType;
import com.kerosene.kfe.ledger.domain.LedgerPosting;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Coordinates a locked balance mutation and its immutable posting atomically. */
public final class LedgerService {
    private final LedgerAccountPort accounts;
    private final LedgerPostingPort postings;
    private final Clock clock;
    private final LedgerMutationObserver observer;

    public LedgerService(LedgerAccountPort accounts, LedgerPostingPort postings, Clock clock) {
        this(accounts, postings, clock, (balance, transition) -> { });
    }

    public LedgerService(LedgerAccountPort accounts, LedgerPostingPort postings, Clock clock,
            LedgerMutationObserver observer) {
        this.accounts = Objects.requireNonNull(accounts, "accounts is required");
        this.postings = Objects.requireNonNull(postings, "postings is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.observer = Objects.requireNonNull(observer, "observer is required");
    }

    public LedgerBalance reserve(Command command) { return apply(command, LedgerMovementType.RESERVE); }
    public LedgerBalance release(Command command) { return apply(command, LedgerMovementType.RELEASE_RESERVE); }
    public LedgerBalance settleDebit(Command command) { return apply(command, LedgerMovementType.SETTLE_DEBIT); }
    public LedgerBalance credit(Command command) { return apply(command, LedgerMovementType.CREDIT); }

    private LedgerBalance apply(Command command, LedgerMovementType type) {
        Objects.requireNonNull(command, "command is required");
        LedgerBalance balance = accounts.lock(command.walletId(), command.asset());
        if (balance == null) {
            throw new LedgerInvariantViolation("ledger balance not found");
        }
        if (postings.exists(command.operationId(), type)) {
            return balance;
        }
        LedgerBalance.Transition transition = switch (type) {
            case RESERVE -> balance.reserve(command.amountSats());
            case RELEASE_RESERVE -> balance.releaseReserved(command.amountSats());
            case SETTLE_DEBIT -> balance.settleReservedDebit(command.amountSats());
            case CREDIT -> balance.creditAvailable(command.amountSats());
            default -> throw new LedgerInvariantViolation("unsupported ledger mutation " + type);
        };
        LedgerPosting posting = new LedgerPosting(UUID.randomUUID(), command.operationId(),
                command.walletId(), command.asset(), transition.movementType(), command.amountSats(),
                transition.fromBucket(), transition.toBucket(), command.reason(),
                command.correlationId(), command.causationId(), Instant.now(clock));
        if (!postings.appendIfAbsent(posting)) {
            throw new LedgerInvariantViolation("duplicate ledger posting");
        }
        accounts.save(balance);
        observer.afterApplied(balance, transition);
        return balance;
    }

    public record Command(UUID operationId, UUID walletId, String asset, long amountSats,
            String reason, UUID correlationId, UUID causationId) {
        public Command {
            Objects.requireNonNull(operationId, "operationId is required");
            Objects.requireNonNull(walletId, "walletId is required");
            Objects.requireNonNull(asset, "asset is required");
        }
    }
}
