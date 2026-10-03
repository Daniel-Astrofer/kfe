# Balance and derivation participants

`KfeBalanceService` and `KfeDerivationCursorService` now require the durable
maintenance guard independently of HTTP, observers and address issuers. Missing
injection fails closed. Admission precedes repository locks, hashing, saves and
managed-entity changes. Operation names are fixed; wallet IDs, cursor keys and
financial inputs are not stored as admission labels.

## Exact boundaries

- Balance genesis, reservation, settlement, release, available credit, reorg
  reversal, absolute observed writes (both overloads), observed credit and
  defensive zeroing all enter admission before their first effect.
- `requireForUpdate` also enters admission. It returns a locked mutable entity,
  so it is not a pure observational API or a completion proof. Ordinary repository
  reads and the pure primary-balance calculation have not become mutation roots.
- Derivation `nextIndex` admits before the cursor query or managed-state changes.
  A new cursor still starts at zero; existing cursors advance by the original
  algorithm. No new derivation or overflow policy was introduced.
- Invalid nonpositive observed-credit input is rejected without effects and
  without an admission. The short absolute-observed overload delegates once.

All setters require a nonnull guard. Constructors retain their signatures and
unavailable defaults; there is no permissive production fallback. Synchronous
nested calls reuse the admitted workflow, including after drain begins. A later
independent call must admit anew; entity references are not continuation tokens.

## Transaction and completion contract

The balance service still has no transaction annotation. Its caller must preserve
the existing financial transaction. Cursor issuance retains its existing REQUIRED
transaction. Neither service starts a new transaction to conceal missing callers.

Genesis and cursor issuance can finish their *local* admission only when an actual
transaction with observable synchronization is active and commits successfully.
The durable guard defers resolution until completion. Direct construction or
invocation with no observed transaction remains UNCERTAIN even if a mocked or
independently transactional repository returns successfully. An active transaction
without synchronization is rejected before effects. Rollback, failed commit or
unknown completion cannot produce COMPLETED.

All balance locking/financial mutation results are conservatively UNCERTAIN,
including defensive no-op zeroing and successful observed writes. These methods
can return mutable capabilities or publish through a best-effort catch. Their
return, an absent wallet notification target, transaction commit or a transport
reply does not certify the wider financial workflow. Swallowed publication errors
cannot clear the admission. Real publisher V58 child provenance is a separate
contract; this service does not invent callback replay or recipient proof.

This conservative boundary intentionally accumulates durable unresolved rows.
There is no force-clear, TTL cleanup, replay, automatic resume or signer activation.
Do not operate this branch as though every completed financial request drains to
zero. All three unknown coverage blockers and complete-Cell deployment gates
remain in force.

## Evidence and remaining scope

The bounded unit suite uses the real maintenance service, mock durable storage and
actual domain/hash algorithms. It covers every public effect root under drain,
missing injection and storage outage; active behavior, nesting, transaction
completion, debt-first credit, reorg and observed metadata, swallowed publication
failure and unchanged transaction propagation.

The opt-in full-schema suite validates the exact Flyway chain and these exact
entities with Hibernate schema validation. It uses actual Spring Data JPA
repositories, a real transactional cursor proxy, JpaTransactionManager and a
durable PostgreSQL guard on the exclusive `kfe_maintenance_test_full_schema_*`
database. It checks commit, rollback, rejection during drain and a deferred
constraint that makes PostgreSQL reject commit *after* the service returns.
Balance commit/rollback assertions inspect the actual buckets/hash and durable
admission state. Metadata lookup and the event port are mocked in balance tests;
these tests do not prove real publisher delivery or the complete application
context. Only synthetic fixture wallet/user rows are cleaned up; no Flyway clean
or production data operation is permitted.

Existing-cursor concurrency additionally holds the first financial row lock,
observes a second independently persisted admission blocked on that row, starts
drain, and checks both prior workflows commit distinct next indices while fresh
issuance is refused. This does not qualify concurrent creation of an absent cursor.
Nested-transaction regressions also cover a caught REQUIRES_NEW commit failure:
successful outer commit must not clear the failed inner workflow.

Read STATUS for the accepted run totals. This boundary does not qualify all
facade/embedded callers, concurrent creation of an absent cursor, stream replay,
remote financial completion or complete-Cell restore. Those require their own
proof before any coverage blocker can be replaced.
