# KFE Cell operations status — 2026-10-02

Implemented durable ACTIVE/DRAINING control, ADMIN audit/idempotency/revision
checks, persistent admissions, V57/V58, exactly-once continuation claims and
fail-closed uncertainty. Standalone HTTP authorization precedes admission; JWT
verification no longer swallows business failures. Guarded roots include submit,
outbox claims, channel execution and producers, PSBT, day rotation, wallet writes,
UTXO scans, cancellation, payment-request writes and expiry-on-read, notification
claims/delivery/status writes and statement retention. Webhook scheduling captures
a durable child before commit/queueing.

The complete KFE `check` and bootJar passed locally: 747 tests, no failures/skips, including
the real full Flyway chain and disposable PostgreSQL concurrency/restart tests.
CI is configured to enable those tests with pinned dependency revisions; a local
pass is not a claim that hosted CI has run. Final exact totals are recorded in
Deploy's dated Cell checkpoint after coordinator acceptance.

Not yet qualified for safe complete-Cell update. Embedded host security chains,
remaining direct address/key/tax/bootstrap roots, streams/observers and embedded
execution ports, remote completion, crash
reconciliation and full-Cell recovery require evidence. Unknown coverage blockers
remain nonzero; successful HTTP roots conservatively remain uncertain. There is
no force-clear, expiry clearance, automated resume or signer activation.

See [entrypoint inventory](operations/maintenance-entrypoints.md) for exact gaps.
