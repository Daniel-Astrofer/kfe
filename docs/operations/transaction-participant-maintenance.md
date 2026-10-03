# Transaction participant maintenance

The four transaction participants require setter injection of the real
`KfeMaintenanceGuard`. Their existing constructors remain compatible, with
unavailable defaults that reject effects until injection. Setters reject null.
Admissions use fixed operation names; user, wallet, payment-request and
idempotency identifiers are not admission labels.

## Boundaries

| Participant method | Operation | First protected effect |
| --- | --- | --- |
| State machine `transition` | `transaction.transition` | Managed transaction status change, repository save and nested audit |
| State machine `audit` | `transaction.audit` | Redacted audit hashing and audit-service call |
| Idempotency `reserve` | `transaction.idempotency-reserve` | Reservation creation and save |
| Idempotency `complete` | `transaction.idempotency-complete` | Managed idempotency transaction/status changes and save |
| Outbox `enqueueExternal` | `transaction.outbox-enqueue` | Payload serialization, hashing and outbox save |
| Internal request `lockAndValidate` | `transaction.internal-request-lock` | For-update query and return of locked mutable entity |
| Internal request `markPaid` | `transaction.internal-request-mark-paid` | Managed payment-request paid state/transaction changes and save |

Drain, unavailable injection and admission-storage failure reject these roots
before effects. Internal-request public-ID cleaning and rejection of an
unsupported caller rail/direction remain pure validations before admission.
Missing or blank public IDs return null, and `markPaid(null, ...)` returns without
admission. Ordinary idempotency lookup, response recovery and canonical request
hashing remain accessible without admission, including their original conflict,
pending and missing-record errors. A lookup is not permission to call a later
mutation during drain.

The original transition graph, including self transitions, is unchanged. Audit
payload construction still merges caller payload after the transaction ID and
idempotency hash. Reservation uniqueness/conflict propagation, canonical request
hash encoding, ordered outbox serialization and positive fee-tier filtering are
unchanged. INTERNAL payment requests and platform LIGHTNING loopback requests
still settle through the internal ledger; onchain requests remain rejected by
this path. Existing expiry, wallet, amount, OPEN and SETTLED checks are preserved.

## Transactions and uncertainty

Only state-machine `transition` retains its existing REQUIRED transaction
annotation. Standalone `audit`, idempotency, outbox and internal-request methods
retain their absence of transaction annotations. Callers still own atomicity and
the for-update lock transaction. Admission does not create a financial transaction
or repair a caller invoking a service without its Spring proxy.

Every admitted participant supplies a false completion predicate. A successful
method return, an outbox ID, a reservation entity, a locked payment request or an
observed transaction commit does not prove completion of the surrounding
financial workflow. The durable guard defers resolution until transaction
completion when it is observable, and resolves uncertain for commit, rollback
or unknown completion. A direct call without an observable transaction also
remains uncertain. An active transaction without synchronization rejects effects.
Failure after managed-state changes remains uncertain; this boundary does not
claim to roll back an unproxied call or undo an in-memory entity.

Synchronous nested participants share the current admitted workflow and may
finish after drain starts. Their conservative predicates also make that parent
uncertain even if its own predicate reports certainty. A later independent call
must admit again. Returned entities, public IDs and outbox IDs are not durable
continuation tokens. Resolution-storage failure leaves durable uncertainty and
does not change a successful participant's financial result.

These leaves can accumulate unresolved admissions deliberately. There are no
force-clear flags, expiry-based proof, automatic resume or signer activation.
Unknown coverage blockers and complete-Cell deployment gates remain in force.

## Verification handoff

`KfeTransactionParticipantMaintenanceTest` uses the real maintenance service,
mock storage, real domain entities, hashing and JSON serialization. It covers all
seven roots under drain/default/outage with pre-effect and unchanged-entity
assertions; active results; transaction commit/rollback/unknown completion;
unobservable transactions; nesting after drain and parent uncertainty; returned
locked-entity reuse; serialization, hash, audit, lock and save failures; resolution
failure; pure lookup/hash conflicts and no-ops; and annotation preservation.
The existing state-machine and internal-request suites explicitly inject
`MaintenanceTestFixture.active()` so the real service admits their fixtures.

Coordinator acceptance, 2026-10-03: this suite and both existing suites compiled
and passed in the full check/bootJar run recorded in STATUS. These transaction
participant fixtures still use mock persistence; separate real JPA balance/cursor/
audit evidence must not be generalized to the complete submit context, idempotency
concurrency, payment-request settlement or provider recovery. Wider caller coverage
and deployment readiness remain unqualified.
