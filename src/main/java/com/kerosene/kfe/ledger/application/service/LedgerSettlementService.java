package com.kerosene.kfe.ledger.application.service;

import com.kerosene.kfe.ledger.application.port.out.LedgerAccountPort;
import com.kerosene.kfe.ledger.application.port.out.LedgerPostingPort;
import com.kerosene.kfe.ledger.domain.LedgerBalance;
import com.kerosene.kfe.ledger.domain.LedgerBucket;
import com.kerosene.kfe.ledger.domain.LedgerInvariantViolation;
import com.kerosene.kfe.ledger.domain.LedgerMovementType;
import com.kerosene.kfe.ledger.domain.LedgerPosting;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Atomic internal settlement coordinator. Wallet locks are acquired in a stable
 * UUID order so opposite transfers cannot deadlock at the ledger boundary.
 */
public final class LedgerSettlementService {
    private final LedgerAccountPort accounts;
    private final LedgerPostingPort postings;
    private final Clock clock;

    public LedgerSettlementService(LedgerAccountPort accounts, LedgerPostingPort postings, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts is required");
        this.postings = Objects.requireNonNull(postings, "postings is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public void settle(Command command) {
        Objects.requireNonNull(command, "command is required");
        if (command.debitWalletId().equals(command.creditWalletId())) {
            throw new LedgerInvariantViolation("settlement source and destination must differ");
        }
        boolean debitFirst = command.debitWalletId().toString()
                .compareTo(command.creditWalletId().toString()) < 0;
        LedgerBalance first = accounts.lock(debitFirst ? command.debitWalletId() : command.creditWalletId(), command.asset());
        LedgerBalance second = accounts.lock(debitFirst ? command.creditWalletId() : command.debitWalletId(), command.asset());
        LedgerBalance debit = debitFirst ? first : second;
        LedgerBalance credit = debitFirst ? second : first;
        if (postings.exists(command.operationId(), LedgerMovementType.SETTLE_DEBIT)) {
            return;
        }
        debit.settleReservedDebit(command.amountSats());
        credit.creditSettlement(command.amountSats());
        Instant at = Instant.now(clock);
        LedgerPosting debitPosting = new LedgerPosting(UUID.randomUUID(), command.operationId(),
                command.debitWalletId(), command.asset(), LedgerMovementType.SETTLE_DEBIT,
                command.amountSats(), LedgerBucket.LOCKED, null, command.reason(),
                command.correlationId(), command.causationId(), at);
        LedgerPosting creditPosting = new LedgerPosting(UUID.randomUUID(), command.operationId(),
                command.creditWalletId(), command.asset(), LedgerMovementType.CREDIT,
                command.amountSats(), null, LedgerBucket.AVAILABLE, command.reason(),
                command.correlationId(), command.causationId(), at);
        if (!postings.appendIfAbsent(debitPosting) || !postings.appendIfAbsent(creditPosting)) {
            throw new LedgerInvariantViolation("settlement posting was not unique");
        }
        accounts.save(debit);
        accounts.save(credit);
    }

    public record Command(UUID operationId, UUID debitWalletId, UUID creditWalletId,
            String asset, long amountSats, String reason, UUID correlationId, UUID causationId) {
        public Command {
            Objects.requireNonNull(operationId, "operationId is required");
            Objects.requireNonNull(debitWalletId, "debitWalletId is required");
            Objects.requireNonNull(creditWalletId, "creditWalletId is required");
            Objects.requireNonNull(asset, "asset is required");
            if (amountSats <= 0L) {
                throw new LedgerInvariantViolation("amountSats must be positive");
            }
        }
    }
}
