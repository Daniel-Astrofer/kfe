# Fee settlement and balance movement maintenance

This bounded maintenance boundary covers `KfeFeeSettlementService` fee credit,
reorg reversal and restoration, and `KfeBalanceMovementRecorder.record`.
It does not grant release authority or establish complete Cell coverage.
See [the maintenance contract](cell-maintenance.md) and
[the entrypoint inventory](maintenance-entrypoints.md).

## Admission boundary

Both components require setter injection of the real `KfeMaintenanceGuard`.
Their constructor defaults are `KfeMaintenanceGuard.unavailable()`. Missing
injection, DRAINING rejection or admission-store failure refuses new work with
the maintenance service's 503 exception before any movement existence check,
system-profit wallet lookup, entity construction/setters, ledger persistence,
balance lock/mutation, audit write or fee-skip metric.

The bounded operation names are:

| Entry point | Operation |
| --- | --- |
| `creditKeroseneFee` | `fee.credit` |
| `reverseKeroseneFeeForReorg` | `fee.reverse-reorg` |
| `restoreKeroseneFeeAfterReorg` | `fee.restore-reorg` |
| `record` | `balance-movement.record` |

Each fee method keeps its existing pure no-op for a null transaction, missing
transaction ID or nonpositive fee before admission. The normalized
`profitSegregationMode` and `profitReconcileWithVault` configuration reads also
remain available during drain or without injection. The movement recorder has
no input no-op: null transaction IDs, zero amounts and signed non-idempotent
movements retain their existing persistence behavior and require admission.

## Financial and transaction contracts

Fee credit still checks for an existing credit, resolves the SYSTEM_PROFIT
wallet, records the unique movement first, and credits the exact fee only if
the recorder reports a new row. Existing-credit and lost-race paths retain the
fee-skip metric. Audit event names, statuses and payloads are unchanged.

Reversal still requires the original credit and no existing reversal. It
records `REVERSAL_KEROSENE_FEE` from `AVAILABLE_OR_DEBT` to `CHAIN_REORG` before
calling the existing available/debt reversal and recording its original audit
payload. Restoration still requires a reversal and no existing restoration;
it records `RESTORE_KEROSENE_FEE` from `CHAIN_REORG` to `AVAILABLE_OR_DEBT`
before crediting the fee. Neither path changes balance/debt algorithms.

The recorder retains the canonical idempotent movement-type precheck, all
movement fields and return values. It still swallows `DataIntegrityViolationException`
for an idempotent movement with a nonnull transaction ID and returns false;
other integrity failures propagate. This patch does not reinterpret the
existing exception handling or unique-index semantics.

Neither component previously declared a transaction annotation, and none is
added. Caller transaction propagation, repository behavior and financial
atomicity remain as before. Admission does not create a new financial
transaction or flush a movement to prove a commit.

## Completion and nested work

Every admitted outcome supplies a false completion predicate. Successful
writes, existing-row skips, missing-prerequisite skips, swallowed duplicate
errors and direct calls without a transaction remain UNCERTAIN. An observed
commit does not turn these outcomes into completion proof. Rollback, unknown
completion and propagated errors likewise cannot clear the durable admission.
Failure to persist resolution leaves the existing blocker unresolved.

Both components share the real maintenance service's synchronous admitted
workflow. A parent admitted before drain can call these methods, including
the nested fee-to-recorder call, after drain begins without requesting a new
root admission. Their false predicates preserve the parent's uncertainty even
when the parent returns a successful result or catches an error. After the
parent returns, a fresh invocation requires fresh admission. This boundary
does not establish asynchronous continuation provenance.

An actual transaction without active completion synchronization is refused
before financial effects and leaves its admission unresolved. Direct calls
without a transaction resolve conservatively as uncertain. These leaves do
not expire, reconcile, retry or manually clear uncertain admissions; use the
shared maintenance contract for operational evidence and recovery decisions.

## Verification limits

The two existing unit suites explicitly install
`MaintenanceTestFixture.active()`, which supplies a real `KfeMaintenanceService`
backed by an ACTIVE mock store. The new `KfeFeeMovementMaintenanceTest` also
uses the real service with a mock store. Its cases cover all four roots for
drain, missing injection and store outage before effects; original movement
fields and fee/audit ordering; idempotent skips and prerequisites; caught
duplicate errors inside a drained parent; successful nested work during drain;
direct invocation and commit/rollback/unknown completion; pure fee no-ops;
and the recorder's null-ID, zero and signed movement semantics.

Coordinator acceptance, 2026-10-03: this suite and both existing suites compiled
and passed in the complete check/bootJar run recorded in STATUS. The transaction tests simulate
Spring synchronization and do not claim real PostgreSQL commit evidence.
No shared API, schema, inventory, index, status, build or coverage blocker is
changed, and this patch does not authorize deployment or signer activation.
