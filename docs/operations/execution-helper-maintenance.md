# Execution transaction helper admission

`KfeExecutionTransactionHelper` requires its mandatory injected
`KfeMaintenanceGuard`; direct construction defaults to unavailable and the setter
rejects null. Admission precedes repository locks, provider/RPC lookup and managed
entity changes. Independent calls in DRAINING, without injection or with admission
storage unavailable propagate 503 before financial effects or callback capture.

| Public mutation root | Admission operation |
| --- | --- |
| `prepare` | `execution-helper.prepare` |
| `recordOutboundBroadcast` | `execution-helper.record-outbound-broadcast` |
| `touchOutboundConfirmations` | `execution-helper.touch-outbound-confirmations` |
| `settleOutboundWhenConfirmed` | `execution-helper.settle-outbound-when-confirmed` |
| `settleOutbound` | `execution-helper.settle-outbound` |
| `settleOutboundLightning` | `execution-helper.settle-outbound-lightning` |
| `markUnknown` | `execution-helper.mark-unknown` |
| `markRetryableFailure` | `execution-helper.mark-retryable-failure` |
| `markFinalFailure` | `execution-helper.mark-final-failure` |
| `markOutboundConflicted` | `execution-helper.mark-outbound-conflicted` |
| `markRequiresReconciliation` | `execution-helper.mark-requires-reconciliation` |

The three compatibility overloads delegate directly to their guarded deeper
overloads. The static fee validator and record factories remain pure and need no
admission. Synchronous nested helper calls share an existing admitted workflow and
can finish during drain. Independent calls require new admission. Existing public
transaction annotations, claim fencing, reserves, movements, fees, idempotency,
retry policy, reconciliation and terminal-outbox behavior are preserved.

Every mutation root supplies `certainCompletion=false`. Local commit, a terminal
shortcut, a positive settlement return, provider return and caught financial or
remote errors do not certify maintenance completion. No unavailable default is
replaced with an ACTIVE fallback or a maintenance-status precheck.

`runAfterCommitAsync` captures a V58 child named
`execution-helper.after-completion` through `scheduleContinuation` while the
parent is current, before commit or enqueue. For an active transaction the child
waits for proven commit; rollback cancels only the unstarted waiting child. With
no transaction, the child is persisted READY before enqueue. The executor runs on
a fresh virtual thread in production so callbacks cannot join the completed
Spring transaction. A package-private executor seam permits deterministic tests
that dequeue only after transaction cleanup; a direct executor during Spring
completion is rejected by the shared guard and leaves the child unresolved.

The child runs the whole callback through nested `executeMutation` with a false
completion predicate. Existing hook and per-provider error logging remains.
Confirmed settlement no longer starts a detached nested virtual thread: observed
resync and notification both execute inside the captured child. Broadcast peer
exposure, deposit observation and notifications retain their existing ordering.
Callbacks can execute during drain using captured provenance without fresh root
admission. Successful delivery and swallowed remote failures both remain
UNCERTAIN. Capture, release, claim or executor failure cannot manufacture completed
callbacks; unresolved durable children continue to block maintenance.

The existing helper suite explicitly supplies a real maintenance service with an
ACTIVE mock store and a deterministic transaction-free executor. Financial
assertions are retained; settlement results are not mocked. The new
`KfeExecutionHelperMaintenanceTest` covers all 14 mutation signatures under drain,
uninjected construction and storage outage; nested admission during drain;
capture/commit/enqueue/claim ordering; rollback cancellation; whole-action child
ownership; successful and caught-error uncertainty; executor rejection; invalid
transaction context; child capture/claim outage and unchanged static blockers.
Its persistence and financial boundaries are mocked, with real guard and Spring
transaction synchronization. The worker was interrupted before final verification;
the coordinator completed integration/compilation and included both suites in the
passing full check/bootJar (see [STATUS](../STATUS.md)). Generic child persistence
and the transaction-publisher consumer were verified with actual PostgreSQL;
helper financial recovery itself is not established by those tests.

Preserved algorithm concern, separate from this admission change:
`tryReconcileConflicted` starts `allInputsFree=true`, sets it false for a non-null
outpoint (an existing unspent UTXO), and only sets `anyInputSpent=true` for a null
outpoint. Thus all-null input probes leave `allInputsFree=true` and choose reserve
release before replacement search. `BitcoinCoreRpcClient.queryOutpoint` also
returns null on caught RPC errors. These semantics conflict with the helper's
comments and require a separate financial review; this patch intentionally does
not change them or treat maintenance tests as proof of refund correctness.

Coverage and readiness gaps remain: upstream producers, remote reconciliation,
other callbacks, crash recovery/replay and PostgreSQL persistence evidence are
not completed by this patch. No shared guard, V58 schema, status or static
`mutationCoverageUnknown`, `callbackCoverageUnknown`, `readSideEffectsUnknown`
blocker is changed. This work implements neither replay nor automatic resume,
and certifies no safe shutdown, deployment or release readiness.
