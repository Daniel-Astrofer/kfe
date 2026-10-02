# KFE Cell operations status — 2026-10-02

Implemented durable ACTIVE/DRAINING control, ADMIN audit/idempotency/revision
checks, persistent admissions, V57/V58, exactly-once continuation claims and
fail-closed uncertainty. Standalone HTTP authorization precedes admission; JWT
verification no longer swallows business failures. Guarded roots include submit,
outbox claims, channel execution and producers, PSBT, day rotation, wallet writes,
UTXO scans, cancellation, payment-request writes and expiry-on-read, notification
claims/delivery/status writes and statement retention. Webhook scheduling captures
a durable child before commit/queueing. The continuation wave adds startup/system
wallets, address issuance/MPC keygen/tax classification, balance/custodial/cold
observations, inbound/network/outbound confirmation and payment-request monitors,
statement/prepared/peer participants, whole execution batches, heartbeats/helper
and direct outbound rail executors. Mandatory injection remains fail-closed.

Dashboard/balance/transaction publications, custodial deposit notifications and
helper after-completion actions now capture V58 children before commit/enqueue.
Helper resync no longer escapes into an unowned grandchild thread. Lightning
SETTLED callbacks use their transactional self proxy, advancing local indices only
after it returns. ZMQ workers admit before callbacks/sequence changes; reactive
flush admits before consuming pending hints and preserves them on rejection.

The complete KFE `check` and bootJar passed locally: **1,211 tests, no failures/skips**, including
the real full Flyway chain and disposable PostgreSQL concurrency/restart tests.
CI is configured to enable those tests with pinned dependency revisions; a local
pass is not a claim that hosted CI has run. Final exact totals are recorded in
Deploy's dated Cell checkpoint after coordinator acceptance. All new consumer
suites were included in the final full run. Four additional actual transaction-
publisher/PostgreSQL cases prove WAITING-before-commit, delivery by captured child
during drain, rollback cancellation and preservation of READY after closure loss.
They do not implement durable replay or prove recipient delivery.

Not yet qualified for safe complete-Cell update. Embedded host security chains,
all remaining low-level/embedded mutation participants, real stream reconnect/
durable source cursors, remote completion, crash
reconciliation and full-Cell recovery require evidence. Unknown coverage blockers
remain nonzero; successful HTTP roots conservatively remain uncertain. There is
no force-clear, expiry clearance, automated resume or signer activation.

The helper runbook also records a pre-existing conflict/refund concern: null
outpoint/RPC-error results are not free-input proof. Admission tests do not qualify
that financial algorithm; it was preserved, not silently rewritten.

See [entrypoint inventory](operations/maintenance-entrypoints.md) for exact gaps.
