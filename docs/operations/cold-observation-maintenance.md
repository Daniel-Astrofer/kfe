# Cold observation maintenance admission

`KfeColdWalletObservationService` requires an injected `KfeMaintenanceGuard`.
Its mandatory `@Autowired` setter rejects null. Direct construction keeps the
unavailable guard and cannot perform financial work until explicitly injected.
Admission uses the shared durable guard, without a status precheck or local
ACTIVE fallback.

The bounded roots are:

| Entrypoint | Operation | Admission boundary |
| --- | --- | --- |
| `reconcileColdWallets` | `cold-observation.reconcile` | Before provider resolution and the active cold-wallet query |
| `observeWallet` | `cold-observation.observe-wallet` | Before wallet locks/lookup, index rebuild, descriptor resolution/scanning and gap-address writes |
| `ingestZmqRawTx` | `cold-observation.ingest-zmq-raw-tx` | Before the transaction template, wallet lookup, outpoint probes and history/balance writes |
| `refreshConfirmations` | `cold-observation.refresh-confirmations` | Before provider resolution, history lookup and confirmation probes |
| `recordColdPsbtBroadcast` | `cold-observation.record-psbt-broadcast` | Before idempotency lookup, history writes and publication |
| `touchColdConfirmations` | `cold-observation.touch-confirmations` | Before the row lock or any managed-entity confirmation/status change |

Fresh roots fail with maintenance admission unavailable (503) during DRAINING,
storage outage or missing guard injection. The scheduled tick catches maintenance
rejection and pauses without providers, queries, scans, writes or publication.
Its configured batch limit and per-wallet exception handling remain unchanged.
Null wallet observations/refreshes and null/empty ZMQ arguments remain effect-free
returns; a blank broadcast txid keeps its existing validation error.

An admitted synchronous parent can finish these nested operations during DRAINING.
The scheduled batch, observation/refresh/touch chain and calls from other admitted
services reuse the shared current root. An independent later invocation must admit
anew. Wallet IDs, transaction IDs, blockchain txids, PSBT workflow IDs and existing
idempotency rows are financial identities, not maintenance provenance. This patch
does not capture or invent ancestry for external ZMQ producers or asynchronous
callbacks.

The existing algorithms are preserved: per-wallet serialization, descriptor and
address outpoint merging, mempool spend/change adjustments, gap-address materialization,
wallet-scoped spend attribution, partial-spend and legacy idempotency handling,
quality-aware observed balances, PSBT amounts/fees, finality threshold and notification
behavior. Chain collection remains outside the short write transaction; existing
transaction annotations/templates are retained. Cold confirmation updates retain
the existing monotonic policy and full-ring probe skip; this admission patch does
not add a new reorg algorithm or change handling of lower confirmations.

Every admitted root supplies `certainCompletion=false`. Caught descriptor/address
scan, index, outpoint, confirmation, balance, notification and display-refresh
failures cannot manufacture completion. Successful chain reads, local writes,
idempotent returns or transaction commit also do not prove remote or after-commit
delivery. The shared guard retains UNCERTAIN admissions (or unresolved IN_FLIGHT
admissions if resolution persistence fails). No timeout, expiry, retry or successful
later observation clears them. Operational recovery needs separate coordinator-owned
evidence and implementation; this patch provides no recovery or force-clear path.

The existing service test now injects the real `KfeMaintenanceService` with an
explicit ACTIVE mock-store fixture. `KfeColdObservationMaintenanceTest` uses that
same real guard with controlled ACTIVE/DRAINING storage and real Spring transaction
synchronization. It covers all six roots under drain/outage/missing injection,
known-ID rejection before managed-entity changes, nested drain completion, descriptor
and gap-address effects, batch limits, caught failures, idempotency/finality,
commit/rollback and after-commit uncertainty. Financial repositories and remote
boundaries are mocked; this is not database rollback, RPC or callback provenance
qualification. Full verification and shared integration remain coordinator-owned.

Worker verification on 2026-10-02 compiled the edited service and both test suites
with `javac` into an isolated temporary directory and ran their JUnit selections:
49 tests passed, none skipped. It used cached dependency jars and existing compiled
project classes, without Gradle or shared build writes. This focused result does
not replace a full source build, PostgreSQL tests or coordinator integration.

`mutationCoverageUnknown`, `callbackCoverageUnknown` and `readSideEffectsUnknown`
remain intact. The coordinator owns shared guard/store/schema/status changes and
other entrypoints. This bounded change is not a complete Cell drain certificate,
release approval, deployment permission, signer activation or automatic resume.
