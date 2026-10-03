# Lightning liquidity and audit maintenance

`KfeLightningLiquidityService` and `KfeAuditLogService` require setter injection
of the real `KfeMaintenanceGuard`. Their constructor default is unavailable:
missing wiring, rejected drain admission or admission-store failure stops work
before reservation lookup/lock/write, managed reservation mutation, enabled
breaker probe/latch evaluation, audit hashing, appender locking, persistence or
structured logging. Admission failures propagate to callers.

## Boundaries and unchanged policy

Reservation admission covers the existing transaction-id idempotency lookup,
the existing pool advisory lock (`0x4B46454C4E4C5154`), free-capacity probe and
HELD insertion. Minimum capacity, held subtraction and duplicate-insert handling
retain their existing behavior. Consume/release admit before finding and changing
a reservation; only HELD rows transition, and null transaction IDs remain no-ops.

When the configured breaker floor is positive, admission precedes every capacity
probe, stress lookup and latch change. Floor, sustained stress and 1.2-times
recovery hysteresis are unchanged. A disabled floor returns false without
admission or effects. `isLive`, outbound/held/free capacity and `canCoverOutbound`
remain observations accessible during drain and admission outage; they do not
change the breaker latch or reservations.

Both audit entrypoints admit before hashing or appender effects. Sanitization,
genesis/previous hash chaining, global transaction-scoped appender lock and
structured audit logging retain their existing ordering and payload policy.
`record` retains REQUIRED propagation; `recordInNewTransaction` retains
REQUIRES_NEW. Existing cautions about uncommitted transaction foreign keys and
calling REQUIRES_NEW while holding the appender lock still apply.

## Completion and drain behavior

An already admitted synchronous parent can invoke these leaves after drain
begins using the real guard's nested workflow. A fresh invocation is rejected.
No separate asynchronous continuation is introduced by these leaves.

Reservation and enabled-breaker results always report uncertain completion.
Remote capacity probes, swallowed probe errors and a caught duplicate insert do
not prove durable workflow completion. Even the reservation's idempotent early
return is conservatively uncertain. Audit results always remain uncertain:
database commit does not prove structured-log delivery, and the existing JSON
serialization fallback must not clear the admission. A caller swallowing a
nested error cannot turn that admission into a certain completion.

Only local consume/release work can report certain completion, and only after
the guard observes an active synchronized transaction commit. Direct calls
without an observable transaction, rollback and unknown completion remain
uncertain. These predicates never introduce a transaction or change propagation.
An unresolved admission is a blocker requiring reconciliation; this patch adds
no automatic clearance or release/deployment authority.

## Evidence and limitations

`KfeLiquidityAuditMaintenanceTest` uses a real `KfeMaintenanceService` with a
mock store to cover drain, missing injection and storage outage before effects;
active results, pure observations, nested calls after drain, transaction
completion, duplicate/probe/serialization/logging uncertainty and the declared
transaction propagation annotations. Existing liquidity, concurrency and audit
unit suites explicitly inject `MaintenanceTestFixture.active()` (a real guard
with a mock store).

Coordinator acceptance, 2026-10-03: these new and existing suites compiled and
passed in the full check/bootJar run recorded in STATUS. Their synchronization is
synthetic. Separate full-schema tests additionally use the exact audit JPA entity,
repository and transactional proxy to prove REQUIRED rollback and REQUIRES_NEW
survival across outer rollback on PostgreSQL. The actual append-only trigger
rejects deletion; synthetic forensic rows are retained, never removed by disabling
that trigger. The structured logger is mocked. Real logging, uncommitted financial
foreign-key cases, concurrent chaining and liquidity advisory-lock/terminal-state
concurrency remain unqualified. No custody/provider action or deployment was run;
unknown coverage and readiness blockers remain in force.
