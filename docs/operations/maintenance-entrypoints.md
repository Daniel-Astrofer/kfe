# KFE maintenance HTTP entrypoints

Inventory date: 2026-10-03, updated after coordinator integration. The standalone
hook, V57/V58, JWT exception-scoping repair and bounded wallet, payment-request,
cancellation, producer, notification and webhook batches are implemented/tested.
The continuation wave also integrates bootstrap/address/key/tax, observations,
network/payment monitors, outbox/rail/helper/prepared/peer participants, statements,
publisher/custodial/helper V58 children and guarded ZMQ/reactive dispatch.
Balance and derivation cursor participants now have their own boundaries, with
actual JPA/PostgreSQL transaction verification rather than inferred caller coverage.
Fee/movement and liquidity/audit leaves also have independent admission boundaries;
these do not qualify upstream quorum or remote-provider completion.
The next bounded wave independently admits settlement evaluation/quorum and
direct Vault MPC, approval and notification adapter effects. Remote completion
and complete embedded dispatch remain unqualified, even after these starts are guarded.
Read with `cell-maintenance.md` and the bounded service runbooks. This inventory
does not certify complete-Cell shutdown or remove coverage uncertainty.

## Standalone integration

`KfeMaintenanceHttpBarrier` is deliberately neither a component nor a filter bean.
It has constructors `(KfeMaintenanceGuard)` and
`(KfeMaintenanceGuard, String internalSharedSecret)`. The first constructor leaves
internal authentication unavailable and fails closed for internal routes. Production
must supply the same existing `kfe.internal.shared-secret` used by the three
internal controllers. Do not supply an admission stub or a permissive authenticator.

The hook is:

```java
public static HttpSecurity register(
        HttpSecurity http, KfeMaintenanceGuard guard, String internalSharedSecret)
```

Implemented coordinator-owned signature and tail for the existing
`KfeStandaloneSecurityConfiguration.kfeSecurityFilterChain` signature and tail:

```java
public SecurityFilterChain kfeSecurityFilterChain(
        HttpSecurity http,
        KfeJwtAuthenticationFilter jwtAuthenticationFilter,
        CorsConfigurationSource corsConfigurationSource,
        KfeMaintenanceGuard maintenanceGuard,
        @Value("${kfe.internal.shared-secret:}") String internalSharedSecret) throws Exception {
    // Keep the existing CORS, CSRF, session, URL authorization and JWT setup.
    // After that setup, immediately before the existing http.build():
    KfeMaintenanceHttpBarrier.register(http, maintenanceGuard, internalSharedSecret);
    return http.build();
}
```

Required new imports in that coordinator-owned file:
`com.kerosene.kfe.maintenance.KfeMaintenanceGuard` and
`com.kerosene.kfe.maintenance.KfeMaintenanceHttpBarrier`. `@Value` already exists.
The injected guard is the existing `KfeMaintenanceService` backed by
`JdbcKfeMaintenanceStore`; no new dependency or schema is needed for this hook.

The hook calls `addFilterAfter(barrier, AuthorizationFilter.class)`. Effective
order is existing JWT authentication, anonymous/context and exception-translation
filters, URL authorization, HTTP admission, then MVC/method authorization and
business handlers. Method authorization and controller credentials remain active.
Apply the same hook to every host/embedded security chain serving these routes;
the standalone chain is conditional on `kfe.standalone=true` and cannot protect an
embedded host by itself. Construct inside the hook, once per applicable chain.
If a host elects to expose a filter bean, it must disable automatic servlet-filter
registration and still insert it in the security chain at this position.

Verify that the security proxy maps REQUEST, ASYNC, ERROR, FORWARD and INCLUDE when
those dispatcher types can reach business handlers in the host. This filter uses
`GenericFilterBean` and does not skip redispatches. Keep Spring's strict firewall;
do not relax path handling to make an exemption match. The filter checks both the
raw URI without context path and the container's servletPath + pathInfo. A
disagreement on a financial route returns a fixed 400 without admission. Empty
mapped paths use the raw URI. Exemptions are exact paths/methods; query strings
do not select exemptions, trailing slashes and extra segments do not inherit one.

## Admission and authentication behavior

Every method under `/kfe`, `/api/admin/kfe` and `/internal/kfe`
(root or descendants) enters `executeMutation("http.financial-root", ...)`, except
the audited controls below. The name is fixed and bounded; IDs, query strings,
credentials and invoice data are never persisted as operation names. Unknown
future routes, including callbacks and side-effecting GET/HEAD, are guarded.
Admission/drain serialization is the real durable guard/store's responsibility;
there is no status-check-then-execute race in the filter.

Guarded user routes require authenticated, non-anonymous Spring authentication and
a positive numeric user identity, matching the existing JWT/user-ID pipeline.
ADMIN routes additionally require `ROLE_ADMIN`. Internal routes independently
verify `X-KFE-Internal-Secret` using the configured value and constant-time byte
comparison before admission. A JWT cannot substitute for that internal secret.
Internal controllers repeat their checks. Missing configuration returns 503;
missing/invalid credentials return 401; absent ADMIN authority returns 403.
These rejections never call the guard or business chain.

The standalone URL policy intentionally permits anonymous `/api/public/kfe/**`.
That family is excluded from this partial HTTP barrier in both ACTIVE and
DRAINING modes, whether the caller has a JWT or is anonymous. Its existing
security/handler policy still applies; the filter creates no admission for it.
`publicGet` expires due requests and lookup delegates to it. The integrated
service checks capability and actual expiry, then admits before expiry mutation.
Pure unexpired public lookup stays available; expiry during drain rejects before
dirtying/saving the entity. This closes that bounded start, not the other
read-side/callback gaps; `safeToUpdate` remains false. There is no global
authentication bypass and no anonymous admission through this filter. OPTIONS
that reach the guarded families are not admission exemptions. Existing CORS
preflight handling precedes it.

Admission rejection returns fixed JSON with schema `kerosene.kfe-maintenance/v1`,
`errorCode=MAINTENANCE_ADMISSION_REJECTED`, status 503, and `Cache-Control: no-store`.
All filter rejection messages are constants; SQL/provider exception text,
identities, credentials and request data are not reflected. The response buffer
and content length are reset for the fixed envelope while CORS headers survive.
A committed response is never replaced. A downstream `MaintenanceException` is
propagated, because it can follow effects or streaming and is not a new admission
rejection. IOException/ServletException are bridged through the guard and then
re-thrown as the original exception. Other runtime failures and Errors propagate
without blanket catch/rewrite logic.

The coordinator repaired `KfeJwtAuthenticationFilter` to catch verifier failures
only. Business failures propagate unchanged, including after commitment. JWT and
actual standalone-chain regression tests cover this with synthetic signed
credentials; no permissive authentication is added.

## Exact controls preserved

These paths skip *admission only*, after the authentication checks above and
existing URL authorization. Controller/method authorization still executes.
GET controls also accept HEAD. No prefix-wide maintenance/admin exemption exists.

| Method | Path | Local evidence |
| --- | --- | --- |
| POST | `/api/admin/kfe/maintenance/drain`, `/resume` | Actual frozen ADMIN commands; operator derived by controller, durable transition in guard. |
| GET/HEAD | `/api/admin/kfe/maintenance/status` | Guard observation; retains all unknown blockers. |
| GET/HEAD | `/api/admin/kfe/audit/latest`, `/events`, `/transactions/{UUID}` | `KfeAuditAdminService` reads repository data/hash roots. |
| POST | `/api/admin/kfe/audit/root` | Despite POST, computes a Merkle root with reads/hashing, no signed-root activation or write. |
| GET/HEAD | `/api/admin/kfe/reserves/overview` | Repository aggregation and provider tip/channel/balance probes; no financial mutation in service. |
| GET/HEAD | `/api/admin/kfe/reserves/psbts`, `/{UUID}` | `KfePsbtWorkflowService.list/get` repository reads. Signed/broadcast descendants remain guarded. |
| GET/HEAD | `/api/admin/kfe/channels`, `/rebalance/jobs`, `/capacity/jobs`, `/capacity/signals` | Channel snapshots, queue reads, in-memory signal snapshot. All POST decisions/process/scan routes remain guarded. |
| GET/HEAD | `/internal/kfe/rail-health/custody-provider`, `/external-providers` | Provider availability probes after shared-secret authentication. |
| GET/HEAD | `/internal/kfe/audit-integrity/root` | Credentials still checked; adapter currently throws UnsupportedOperationException for signed roots. Availability exemption does not make that adapter healthy. |

Dynamic control details accept only a complete UUID path segment. Liveness,
readiness and actuator health routes are outside the financial families and
retain their existing authentication/authorization policy. The filter does not
replace their policy with permitAll.

## Conservative completion and dispatch lifecycle

Every admitted business HTTP root supplies a completion predicate of `false`,
including a synchronous 2xx response. HTTP return, flush, 202, servlet onComplete,
timeout, error rendering and transaction commit do not prove financial/remote
completion. The real guard resolves the durable admission as UNCERTAIN, or leaves
IN_FLIGHT if resolution persistence fails. A bound outer transaction still defers
resolution to its observed completion, with certainty false even after commit.
No timeout, expiry, async listener or stream-close callback clears the admission.
Crash before resolution retains the durable IN_FLIGHT row.

This deliberately accumulates unresolved business HTTP admissions until an audited
completion/reconciliation contract exists. There is no force-clear API in this
change. Do not deploy it assuming completed HTTP responses will make the database
safe to update. Even benign ordinary business reads outside the control list
receive this conservative treatment.

Synchronous service/filter nesting reuses the guard's current workflow, so an
already admitted request can finish nested work after drain starts. The barrier
keeps only invocation-local admission-rejection state; it stores no request
attributes, thread-local tokens, static workflow map or reusable continuation
credential. The guard removes its thread-local workflow on all exits. Checked
failures reach that cleanup through the exception bridge. Later requests on the
same thread must admit anew.

After the root returns, a financial ASYNC/ERROR/FORWARD/INCLUDE redispatch must
authenticate and admit anew; DRAINING rejects it. It cannot reuse a stale request
marker. This can interrupt asynchronous response dispatch during drain. The
already running async task may still execute effects, including effects before
redispatch, and stays uncertain; this filter does not authorize its continuation.
An ordinary `/error` renderer is outside the financial families and must remain
observational. Moving business work into such a renderer would require a separate
root boundary. Extending servlet continuation behavior requires an audited
integration with durable child provenance. The coordinator has now added
`scheduleContinuation(String operation, Executor executor, Runnable work)` to the
guard, with store capture/release/claim methods and V58 WAITING/READY/CANCELLED
states. It requires a currently admitted parent. This HTTP change neither edits
that API/schema nor adopts it as a servlet redispatch credential. Call-site
integration and proof of actual callback/remote completion remain outstanding.

## Actual HTTP route inventory

All local annotated HTTP controllers were scanned, including runtime health and
the service-package capacity controller (which is scheduled, not an HTTP MVC
controller). This table describes perimeter coverage after coordinator integration,
not leaf/remote completion certification.

| Controller(s) | Actual roots and effects | Remaining coverage |
| --- | --- | --- |
| `KfeWalletController` | `/kfe/wallets`: POST create, PATCH label, POST archive/address rotate/cold PSBT; GET list/names/UTXOs. | Create commits a pending wallet before quorum/MPC, then activation and post-activation chain hooks. `KfeWalletNetworkService.listUtxos` reads provider UTXOs/descriptor scans; no local save found. Direct service/embedded mutation callers are outside HTTP. |
| `KfeTransactionController`, `KfeOnrampController` | `/kfe/transactions`: submit, quote, cancel, history/detail and GET onramp URLs. | All guarded, including benign quote/history/directory reads. Execution queue, cancel/compensation and provider follow-up completion need their own evidence. |
| `KfePaymentRequestController` | `/kfe/payment-requests`: create/list/get/expire/hide/cancel. | GET list/get call `expireIfDue`, which saves expired rows. New address/invoice creation and cancellation callbacks remain unproved. |
| `KfePublicPaymentRequestController` | `/api/public/kfe/payment-requests/{publicId}` and `/lookup`. | Filter exclusion preserves anonymity. Service capability/expiry admission now rejects due expiry during drain before entity mutation; pure lookup remains available. |
| `KfePsbtWorkflowController`, `KfeReservePsbtAdminController` | `/kfe/cold-wallet/psbts`, `/api/admin/kfe/reserves/psbts`: list/get/signed/broadcast. | Ordinary user reads guarded; exact reserve control reads exempt. Remote broadcast/signing and observation callbacks remain uncertain. |
| `KfeChannelAdminController` | `/api/admin/kfe/channels`: read snapshots/jobs/signals; POST capacity scan/process, rebalance process, open/rebalance/close/ppm evaluation and execution. | Evaluations persist decisions; scan can enqueue. Only exact read controls exempt. Scheduled producers/reconcilers need separate admission. |
| `KfeDashboardController`, `KfeReceivingController` | GET `/kfe/dashboard`, `/kfe/users/{receiverIdentifier}/receiving-capabilities`. | Guarded ordinary reads. Receiving capability user-directory/financial API call graph and dashboard after-commit publication remain separate roots/callbacks. |
| `KfeTaxEventController` | GET `/kfe/tax-events` and `/export`, POST `/{eventId}/classify`. | Reads conservatively guarded; classify saves classification. Statement/tax producers outside HTTP are participants of their own roots. |
| `KfeAuditAdminController`, `KfeReserveAdminController`, `KfeMaintenanceAdminController` | Exact control routes listed above. | Maintain role checks; any new path/verb guarded by default. Reaudit exemptions if implementations acquire writes or callbacks. |
| Three `KfeInternal*Controller` classes | `/internal/kfe/wallet-provisioning/primary`, audit-integrity/root, rail-health probes. | Exact read controls stay credential protected. Provisioning guarded before adapter calls wallet service. Embedded financial port invocation bypasses HTTP. |
| `KfeHealthController` | `/healthz`, `/health/live`, `/health/ready`, `/health/dependencies`. | Existing probes retained. Healthy readiness is not maintenance safety. |

No annotated provider callback HTTP controller was found in this local inventory.
Future callbacks inside the three guarded financial families inherit admission and
must authenticate; public-family callbacks and callbacks outside them require a
new explicit boundary under their existing security policy. This does
not claim that remote provider execution stops at the HTTP boundary.

## Scheduled, worker, callback and embedded gaps

These concrete roots are not covered by an HTTP filter. Existing patches in other
files are coordinator/prior work and were not changed. A guard in a downstream
leaf alone is insufficient if a root already writes/enqueues before reaching it.

| Root or callback | Observed behavior and outstanding work |
| --- | --- |
| `KfeExecutionOutboxWorker.drain` -> `KfeExecutionOutboxService.claimDue` -> processor/helper/rail executors | Whole batch, processor, heartbeat, helper and direct rail roots now admit before effects. Nonempty claims remain uncertain; lease tokens cannot authorize fresh processing during drain. Helper after-completion work captures V58 children before commit/enqueue, including formerly detached resync. PROCESSING/UNKNOWN, remote completion and restart replay remain blockers. |
| `KfeChannelCapacityWorker.processPending/processBatch/executeJob`, `KfeChannelRebalanceWorker.processPending/processBatch/executeJob`, `KfeVaultMeshDayRotationWorker.tickCron/tickFixedDelay` | Existing guard patches cover their starts. They do not certify remote channel/Vault completion or independently guard queue producers. |
| `KfeChannelCapacityController.scan` | Scheduled scan/evaluations and queue APIs now guarded. Remote effects/whole scanner recovery are not qualified. |
| `KfeChannelDrainMonitor.scan/inspectChannel` | Each nonempty inspect admits before decisions, queue writes or PPM effects. Complete scheduled/provider failure matrix remains. |
| `KfeChannelMeshInjectReconciler.reconcile/retryPendingCommits/releaseOrphanedReserves` | Batch admission integrated; failed commits/releases stay uncertain. Orphan sweep additionally requires ACTIVE; missing intent remains unresolved rather than cleared by TTL. |
| `KfeOnchainBalanceSyncService.reconcileActiveOnchainWallets/syncWallet/applyObserved` | Scheduled/explicit roots and chain scans now guarded before effects. Conservative uncertainty remains for probe/reorg/provider and publication outcomes. |
| `KfeCustodialDepositObservationService.reconcileCustodialDeposits/observeWallet/ingestZmqRawTx` | All roots admit before scans, finality locks and ledger writes. Deposit notification captures V58 child before commit/enqueue and claims once off-transaction, including during drain. Source/restart/remote recovery remains unqualified. |
| `KfeColdWalletObservationService.reconcileColdWallets/observeWallet/ingestZmqRawTx/refreshConfirmations` | Observation, direct PSBT broadcast recording and confirmation writes now guarded, preserving existing algorithms. Remote and reorg reconciliation remain uncertainty, not readiness evidence. |
| `KfePaymentRequestOnchainMonitor.reconcileOpenOnchainPaymentRequests/observePaymentRequest/settlePaymentRequest` | Provider polling and direct observe/settle roots now admit before locks or financial effects. Actual source/completion/recovery certification remains. |
| `KfePaymentRequestLightningMonitor.reconcileOpenLightningPaymentRequests` and `startStreamSubscription` -> `handleStreamInvoiceUpdate` | Polling, stream callback and explicit fail/expire/settle/reconcile/inspect roots guarded. SETTLED callback now uses transactional self proxy; cursors advance after its return. Real Spring commit-boundary negatives pass, but in-memory cursors are not durable acknowledgements or restart replay. |
| `LndRestLightningClient.subscribeInvoices` | Dedicated subscriber thread invokes the monitor's callback; provider delivery is not a servlet dispatch. Thread-local HTTP admission is not inherited. |
| `KfeNetworkMonitor.reconcileInbound` / outbound confirmation monitor | Each eligible inspection and inbound settlement admits before provider/entity/ledger effects. Scheduled rejection stops the pass; IDs do not prove prior admission. Recovery/completion still unknown. |
| `KfeBitcoinZmqWatcher` -> `KfeColdWalletReactiveRefreshService` | Actual worker callback boundary admits before gap refresh/ingest/target enqueue, and advances local sequence telemetry after admitted handler return. Reactive flush admits before consuming pending targets; rejected ticks preserve hints. Tests use actual callbacks without sockets. Hints/cursors are not persisted; real reconnect, unsigned sequence wrap, restart and source reconciliation remain unqualified. |
| `KfeBalanceReconciliationJob.reconcile` | Some checks only log/record metrics; `refreshStaleColdWallets` reaches mutating cold observation. Separate observational checks from refresh admission. |
| `KfeMonitoredChainAddressIndex` scheduled rebuild | In-memory monitored-address index refresh; no durable financial mutation found in the index itself. Must not treat this as covering its ZMQ/observation consumers. |
| `KfeNotificationOutboxWorker.drain` -> `KfeFinancialNotificationOutboxService.claimDue` -> `KfeNotificationOutboxProcessor.process` | Claims/status/delivery and synchronous batch now guarded. Best-effort delivery stays uncertain; DELIVERED is not recipient proof. |
| `KfeWebhookDeliveryService.publishAfterCommit` / delivery executor | Persists V58 child before commit/enqueue and claims once off-thread. Retry/serialization/interruption failures stay uncertain. No restart/replay runner exists. |
| `KfeDashboardPublisher`, `BalanceEventPublisher`, `TransactionEventPublisher`, `KfeStatementService` | Statement roots admit before flush/upsert/publication, including best effort. Publishers capture V58 children before commit/enqueue and use false completion for delivery. Actual transaction publisher/PostgreSQL commit/rollback/lost-closure tests retain blockers; recipient proof/replay still missing. |
| `KfeStatementRetentionService.purgeExpiredStatements` | New purge guarded; completion requires observed transaction success. |
| `KfeBalanceService` all writes and `requireForUpdate` | Admission before locks/hash/managed state; locking returns a mutable capability and remains uncertain. Financial/publication outcomes stay uncertain. Only genesis with actual observed transaction commit can locally complete; no new propagation added. |
| `KfeDerivationCursorService.nextIndex` | Guard before cursor lock/write; original REQUIRED propagation and algorithm retained. Commit, rollback, drain and PostgreSQL commit-rejection tested with real JPA/proxy; direct no-transaction return stays uncertain. This is not concurrent absent-cursor or complete address/replay qualification. |
| `KfeFeeSettlementService.creditKeroseneFee/reverseKeroseneFeeForReorg/restoreKeroseneFeeAfterReorg`, `KfeBalanceMovementRecorder.record` | Guard before existence checks, profit lookup and writes; original idempotency/financial ordering and absent transaction annotations retained. Even duplicate/prerequisite skips and caught integrity errors stay uncertain. Null/nonpositive fee noops and config reads remain available. |
| `KfeLightningLiquidityService.reserveForTransaction/consumeForTransaction/releaseForTransaction/circuitBreakerOpen` | Reservation and enabled-breaker evaluation guarded before locks/probes/latch changes. Existing pure capacity observations and disabled breaker remain readable. Reserve/breaker outcomes uncertain; only local consume/release with observed synchronized commit may complete. Provider HTLC state and actual advisory-lock/terminal concurrency remain unqualified. |
| `KfeAuditLogService.record/recordInNewTransaction` | Guard before hash/appender lock/write/log; real JPA/PostgreSQL tests prove REQUIRED rollback and REQUIRES_NEW suspension/survival with append-only enforcement. Audit/structured-log outcomes uncertain. Real financial foreign-key scenarios, concurrent audit chaining and remote logging remain unqualified. |
| `KfeTransactionStateMachine.transition/audit`, `KfeTransactionIdempotencyUseCase.reserve/complete`, `KfeTransactionOutboxUseCase.enqueueExternal`, `KfeInternalPaymentRequestSettlementUseCase.lockAndValidate/markPaid` | Independent guards before managed state, hashing/serialization, locks and writes. Original propagation/graph/idempotency/payload/internal-Lightning semantics retained; all admitted results uncertain, including returned mutable handles. Pure lookup/hash and no-public-ID/null-request noops remain available. |
| `KfeBitcoinRuntimeBootstrap.run` -> `KfeSystemWalletService.ensureSystemWallets` / Core ensureWalletLoaded | Startup and accounting-wallet creation now admit before effects. Rejected bootstrap pauses without aborting process and refuses readiness. Actual ADMIN routing to unready pods and RPC wallet recovery still need qualification. |
| `ExternalRailProviderRegistry` ApplicationReady listener | Provider registration/availability initialization, not a newly admitted financial execution by itself; embedded provider effects still require caller review. |
| `KfeFinancialWalletProvisioningAdapter`, `FinancialApi`, embedded participants | Inbound/peer/prepared/helper/direct rail, address issuance, MPC keygen, tax classify, balance/cursor and fee/movement/liquidity/audit roots now guarded. The complete facade/embedded call graph and alternate host security chains remain unqualified; downstream guards alone do not certify upstream effects. Core's current build has no KFE dependency/financial JPA ownership, so embedding must not be inferred from its broad component scan or URL registry. |

`BinarySettlementGate.evaluate/evaluateAndRequirePass` and both gate audit
entrypoints now admit before evaluation, locking, probes, consensus, audit or
signals. `KfeQuorumGateway` and direct `VaultMeshFinancialQuorumAdapter` consensus
calls independently admit before transport, including the legacy context GET.
Direct Vault MPC provisioning and typed remote approval/notification POSTs now
have their own boundaries. These starts remain conservative on every outcome;
validity checks and unsupported legacy approval inputs stay pure. Best-effort
notification transport handling cannot swallow admission rejection.
These boundaries do not implement external completion/reconciliation contracts.
Real provider implementations and alternate embedded dispatchers still require
complete inventory. `FinancialApi` currently delegates mutation starts without
preceding mutation of its own; this observation is not qualification of every
facade caller/host or provider. Jamming checks currently inspect provider HTLC
state without financial writes; do not blanket-disable pure administration probes.
The bounded transaction-participant wave is implemented; its unit contract is
not full submit/JPA/provider qualification. Read the settlement and direct-provider
runbooks for the exact tested scope and mocked proof/transport limits.

Keep `mutationCoverageUnknown=1`, `callbackCoverageUnknown=1` and
`readSideEffectsUnknown=1` exactly as currently returned by `KfeMaintenanceService`.
This file records gaps; it does not decrement those values. A complete caller
inventory, integration of the coordinator's durable-continuation API/V58 and
verification are prerequisites for replacing them with inventory-derived coverage
counts. The presence of that API alone does not close these caller gaps.

## Verification handoff

`KfeMaintenanceHttpBarrierTest` runs the actual filter with the real
`KfeMaintenanceService` and a mocked `KfeMaintenanceStore`, not a permissive guard.
It covers authenticated admission, drain/store failures, GET/HEAD/future callbacks,
exact controls and path variants, preserved public anonymity/exclusion, internal shared-secret checks,
ADMIN roles, nesting and cleanup on success/checked/runtime/Error exits, async
completion/redispatch, committed streams, handled error responses, outer transaction
commit, failed resolution and unchanged unknown blockers. It also exercises a real
`FilterChainProxy` with `KfeJwtAuthenticationFilter`, context/anonymous filters,
exception translation and `AuthorizationFilter`; only JWT verification and durable
storage are mocked boundaries. The hook's insertion order is asserted separately.

The coordinator completed the standalone hook and fixed the historical nesting
fixture's requestURI/servletPath mismatch. Public exclusion/expiry-service and
pipeline tests were included in the accepted full suite. Consult STATUS for the
latest exact run, not the old worker's pending-validation handoff. PostgreSQL
drain/admission races, rollback/restart, full Flyway schema and bounded real JPA
participants have separate accepted tests; mock-store filter tests alone cannot
prove durability. Alternate host chains, real streams, recipient proof and
complete-Cell recovery remain unqualified.
