# Custodial deposit observation admission

`KfeCustodialDepositObservationService` requires an injected `KfeMaintenanceGuard`
through its mandatory `@Autowired` setter. Direct construction remains unavailable
until explicitly supplied a guard; the setter rejects null.

| Root | Admission operation | First protected effect |
| --- | --- | --- |
| `reconcileCustodialDeposits` | `custodial-observation.reconcile` | Provider resolution and active-wallet query, before wallet probes and known-deposit finality reconciliation |
| `observeWallet` | `custodial-observation.observe-wallet` | Wallet lookup, before merged UTXO/descriptor scans and deposit transactions |
| `ingestZmqRawTx` | `custodial-observation.ingest-zmq-raw-tx` | Address-index resolution and wallet lookup, before inbound creation or updates |

Independent roots rejected in DRAINING, during admission-store outage or without
injection perform no scans, transaction opening, managed-entity changes, ledger or
fee writes, statements, audit, observed-balance resync or publication. Public
reactive entries propagate the guard's 503 rejection. The scheduled entry pauses
that tick on maintenance rejection. It does not report reconciliation completed.

The scheduled root encloses both the existing configured wallet batch and the
bounded known-deposit finality query. Admission precedes confirmation RPC and
transaction locking, including managed-entity setters such as `lastChainProbeAt`
and confirmation/status updates that could otherwise flush without an explicit
save. Merged UTXO probes may trigger descriptor scans; observation admits before
those probes. Probes remain outside the service's per-deposit write transactions.

Nested synchronous calls reuse the real guard's current root and may finish when
drain begins after admission. The next independent call must obtain new admission.
Existing per-deposit commits, address matching, transaction normalization,
idempotency, movement-first credit, confirmation thresholds, missing-probe policy,
reorg compensation/restoration, fee handling and notification ordering remain
unchanged. This integration adds no automatic resume or signer activation.

Every root uses `certainCompletion=false`. Successful local commits, successful
RPC returns, caught probe/quote/commit/resync errors and successful or failed
after-commit notifications all retain durable UNCERTAIN admission. Caller
transaction rollback cannot certify completion. Deposit notifications now capture
a V58 child before commit/enqueue. Rollback cancels only an unstarted WAITING child;
commit releases it READY and a transaction-free executor claims it once, including
during drain. The whole notification runs under child provenance with false
completion, preserving uncertainty even when the port catches an error. Executor
rejection/claim failure retains an unresolved child. No notifier or inert raw input
creates phantom work. Parent commit and remote notification return are not shutdown
evidence; the stored child is not a persisted closure or restart/replay runner.

`KfeCustodialObservationMaintenanceTest` uses the real maintenance service with an
explicit ACTIVE/DRAINING mock store and Spring transaction synchronization. It
covers all three roots under drain, store outage and missing injection; no scan or
managed-entity mutation before admission; mandatory injection; nested drain;
scheduled batch/finality completion; caught provider and write failures; resync;
rollback and successful/failed after-commit notification uncertainty; child capture
ordering, once-only claim during drain, missing-port noops, executor/claim outage.
The existing
service tests explicitly inject a real guard with an ACTIVE admission fixture and
retain their inbound, conflict compensation and restoration assertions.

Verification gap: tests have been added but this worker has not executed Gradle,
PostgreSQL or production checks. The coordinator owns those checks and shared
guard/V58 integration. External ZMQ producer coverage, remote reconciliation and
restart/replay and other callback provenance remain separate gaps. No static
`mutationCoverageUnknown`, `callbackCoverageUnknown` or `readSideEffectsUnknown`
blocker is cleared, and this patch does not certify safe shutdown, deployment or
release readiness.
