# Publisher maintenance boundary

`KfeDashboardPublisher`, `BalanceEventPublisher` and `TransactionEventPublisher`
require the injected durable `KfeMaintenanceGuard`. Compatibility construction
defaults to unavailable admission. Missing guard or failed admission/capture
storage rejects before enqueue, dashboard lookup, STOMP/relay or metric effects.
Null users/events, empty transaction payloads and absent transports remain noops.

Each eligible call enters `publisher.<dashboard|balance|transaction>.enqueue`;
nested calls share the currently admitted financial parent. Within that workflow,
`scheduleContinuation` persists a V58 child named
`publisher.<dashboard|balance|transaction>.delivery` before crossing the transaction
or executor boundary. Transaction-bound children wait for proven commit, which
releases them to READY before enqueue. Rollback cancels unstarted children without
publishing; unknown completion leaves them WAITING. Parent completion never
completes a child.

The production executor is `ForkJoinPool.commonPool()`. Work executes
asynchronously on a transaction-free thread after a durable child claim. Calls
without a transaction also capture a child before asynchronous enqueue. No
delivery runs inline in `afterCommit`. A new publisher root is rejected during
drain, while an already captured descendant may be claimed and finish its attempt.
Admission IDs, not timeouts or event payloads, supply that provenance.

Inside the claimed child, delivery enters nested `executeMutation` with a
completion predicate that always returns false. Local STOMP return, remote HTTP
return, the relay's internally swallowed failures and existing publisher catches
do not prove recipient delivery. Children therefore resolve UNCERTAIN even on
normal return. Thrown dashboard/transport errors also retain uncertainty. Metrics
and existing informational publish logs describe local attempts, not recipient
acknowledgement. No fake acknowledgement, expiry, retry, automatic replay or
force-clear is introduced. Executor rejection or failed release/claim leaves
the durable child unresolved according to the shared service contract.

Transport precedence, destinations and payloads are retained: the local broker
takes precedence, the remote dashboard sends only `KFE_DASHBOARD_DIRTY`, transaction
payloads retain their shallow copy, and the balance scalar overload delegates to
the event path. Dashboard construction and balance metrics occur in child work.

`KfePublisherMaintenanceTest` uses the real maintenance service with a mocked
store and a controlled executor that runs queued tasks on a separate thread.
It covers capture before enqueue, commit -> READY -> later claim -> effect,
transaction-free execution, rollback cancellation, unknown completion, drain
rejection/admitted descendants, missing injection/storage, executor rejection,
failed commit release, rejected child claims, unobservable transactions,
normal fire-and-forget uncertainty, thrown/caught errors and the actual relay
with a failing mocked HTTP transport. It also checks noops and existing payload
and transport behavior.

On 2026-10-02, direct Java 21 compilation and JUnit Platform execution passed all
66 test cases using cached dependencies and existing supporting classes. The
three publishers and current Guard/Store/Service sources were compiled into a
temporary directory; shared build output was not changed. No Gradle was run.

Coordinator evidence now supersedes the focused-only build limit: the final full
Gradle check/bootJar passed all suites (see [STATUS](../STATUS.md)). Four actual
transaction-publisher cases also ran with the real JDBC store and PostgreSQL:
capture while the caller TX is open, commit/release and delivery during drain,
caught transport failure remaining uncertain, rollback cancellation, and loss of
the in-memory closure leaving a durable READY blocker visible to a recreated store.

Recipient acknowledgement/reconciliation and recovery of persisted children after
process loss are still not implemented. Queue-loss simulation is not executable
restart replay or complete-Cell readiness. Existing unknown coverage blockers
remain in place.
