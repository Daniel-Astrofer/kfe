# KFE Cell operations status — 2026-10-03

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
The October 3 wave adds independent balance/cursor, fee/movement, liquidity/audit
and state/idempotency/outbox/internal-payment-request participants. Mutation and
locking capabilities admit before effects; pure observations/noops retain their
existing contracts. Financial algorithms and transaction propagation are unchanged.
The next wave adds upstream settlement evaluation/audit, quorum gateway and direct
Vault quorum/MPC and remote approval/notification boundaries before provider effects.
Every outcome remains conservative; validation and unsupported legacy inputs stay
local. Best-effort transport cannot swallow admission rejection.

Dashboard/balance/transaction publications, custodial deposit notifications and
helper after-completion actions now capture V58 children before commit/enqueue.
Helper resync no longer escapes into an unowned grandchild thread. Lightning
SETTLED callbacks use their transactional self proxy, advancing local indices only
after it returns. ZMQ workers admit before callbacks/sequence changes; reactive
flush admits before consuming pending hints and preserves them on rejection.

The complete KFE `check` and bootJar passed locally: **1,776 tests, no failures/errors/skips**, including
the real full Flyway chain and disposable PostgreSQL concurrency/restart tests.
CI is configured to enable those tests with pinned dependency revisions; a local
pass is not a claim that hosted CI has run. Final exact totals are recorded in
Deploy's dated Cell checkpoint after coordinator acceptance. All new consumer
suites were included in the final full run. Four additional actual transaction-
publisher/PostgreSQL cases prove WAITING-before-commit, delivery by captured child
during drain, rollback cancellation and preservation of READY after closure loss.
They do not implement durable replay or prove recipient delivery.

The full-schema suite now executes 14 cases, including exact JPA entities/repositories
and transaction proxies for balance, cursor and audit: real commit/rollback,
PostgreSQL deferred commit rejection, admitted existing-cursor writers across
drain, REQUIRED audit rollback and REQUIRES_NEW forensic survival. The append-only
audit trigger stays enabled and synthetic forensic rows are retained in the
exclusive disposable database. Generic/publisher PostgreSQL cases remain 14.
The two added actual-store quorum cases prove local commit does not clear remote
uncertainty after store recreation and actual drain rejects fresh provider calls
while admitted nested work can finish. Their quorum port is mocked, not live BFT.
New bounded suites contain 47 settlement, 20 Vault provider and 204 remote effect
cases. Accepted synthetic quorum proofs in the adapter fixture use a mocked
verifier and do not establish cryptographic correctness or real release authority.
Provider/event/structured-log ports in these JPA fixtures are mocked; real delivery,
complete submit context and liquidity advisory-lock concurrency are not qualified.

Coordinator also repaired a reproduced guard bug: a caught REQUIRES_NEW commit
failure previously let a committing outer workflow become COMPLETED. Nested
transaction completion now preserves that uncertainty before root resolution;
unobservable nested transactions reject before effects. A root admitted without
an observed transaction cannot gain completion proof by starting one later.
The real PostgreSQL negative and joined-commit/rollback/unknown unit cases pass.

Not yet qualified for safe complete-Cell update. Embedded host security chains,
all remaining low-level/embedded mutation participants, real stream reconnect/
durable source cursors, remote completion, crash
reconciliation and full-Cell recovery require evidence. Unknown coverage blockers
remain nonzero; successful HTTP roots conservatively remain uncertain. There is
no force-clear, expiry clearance, automated resume or signer activation.

The helper runbook also records a pre-existing conflict/refund concern: null
outpoint/RPC-error results are not free-input proof. Admission tests do not qualify
that financial algorithm; it was preserved, not silently rewritten.
The new remote tests also expose a pre-existing outbound-conflicted notification
defect: the client constructs confirmations=-1, rejected by the request contract
before admission/transport. Its four validation cases preserve and document that
failure; they do not certify conflict notification delivery or fix its policy.

See [entrypoint inventory](operations/maintenance-entrypoints.md) for exact gaps.
