# Onchain balance observation admission

`KfeOnchainBalanceSyncService` requires its injected `KfeMaintenanceGuard`.
Construction without injection stays unavailable, and the mandatory setter rejects
null. No permissive fallback or maintenance status precheck admits work.

Scheduled reconciliation admits `balance-observation.reconcile` before provider
resolution or the active-wallet query. Admission failure pauses that tick without
RPC, balance locking/writes, metrics or dashboard publication; it does not report
reconciliation as completed. The existing configured batch bound remains intact.

`syncWallet` admits `balance-observation.sync-wallet` before wallet/provider lookup,
address scans or descriptor resolution. The package-visible chain probe helper
also admits `balance-observation.probe-chain` before any scans. Descriptor probes
can invoke Core `scantxoutset`, so these are effectful operations. Chain probes
remain outside the balance-write transaction.

Valid observations, including the legacy absolute overload, admit
`balance-observation.apply-observed` before opening the write transaction. Existing
wallet-kind checks, quality policy, fresh-live optimistic protection, cold
spendable-bucket clearing and dashboard after-commit behavior are unchanged.
Null/UNKNOWN observations and negative legacy amounts remain effect-free returns.

All admitted paths conservatively resolve with `certainCompletion=false`.
Successful RPC, a partial scan fallback, a swallowed exception, a quality-deferred
write or local transaction commit cannot prove remote/callback completion. Durable
uncertainty remains a blocker rather than being expired or marked complete.
Synchronous nested participants share the current admitted parent and may finish
after drain starts; the next independent invocation must admit anew. This does
not add durable provenance to external observation producers or dashboard hooks.

`KfeBalanceObservationMaintenanceTest` uses the real maintenance service with an
explicit ACTIVE/DRAINING mock-store fixture, mocked financial/RPC boundaries and
Spring transaction synchronization. It covers rejection before effects, mandatory
injection, unavailable storage, nested drain races, batch limits, swallowed probe
failures, commit/rollback and after-commit uncertainty. The existing ApplyObserved
policy suite includes cold-authority and custodial-zero regressions. Tests require
coordinator-owned Gradle execution; this worker has not run Gradle or PostgreSQL.

This bounded service integration leaves `mutationCoverageUnknown`,
`callbackCoverageUnknown` and `readSideEffectsUnknown` intact. Cold observation,
ZMQ/other producers, callback provenance and remote reconciliation still need their
own coverage/evidence. It provides no release, resume or deployment authority.
