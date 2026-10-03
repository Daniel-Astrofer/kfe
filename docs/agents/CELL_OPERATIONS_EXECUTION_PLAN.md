# Cell operations implementation wave

Coordinator: main Codex agent. All continuation-wave workers are now closed;
main owns completion and verification of returned/interrupted work below.
Shared filesystem: this isolated worktree; no edits in the primary checkout.

Coordinator integration checkpoint: all wave agents have been closed. The main
agent reviewed the returned and interrupted batches, completed their integration
fixtures/tests/runbooks and owns any remaining work. Historical single-writer
assignments below no longer indicate active workers. Verification and readiness
are reported by STATUS and Deploy's dated checkpoint, not by worker completion.

## Frozen API

Authenticated `ROLE_ADMIN`, operator derived from authentication:
`POST /api/admin/kfe/maintenance/drain`, `POST /api/admin/kfe/maintenance/resume`,
`GET /api/admin/kfe/maintenance/status`. Commands include changeId, reason,
expectedRevision. Status includes schema `kerosene.kfe-maintenance/v1`, mode,
changeId, revision, observedAt, safeToUpdate, and named blocker counts.
Unknown mutation coverage is a blocker, never implicit permission.

## Single-writer ownership

Worker may edit only:

```
src/main/java/com/kerosene/kfe/maintenance/KfeMaintenanceGuard.java
src/main/java/com/kerosene/kfe/maintenance/KfeMaintenanceStore.java
src/main/java/com/kerosene/kfe/maintenance/KfeMaintenanceService.java
src/main/java/com/kerosene/kfe/maintenance/JdbcKfeMaintenanceStore.java
src/main/java/com/kerosene/kfe/controller/KfeMaintenanceAdminController.java
src/main/java/com/kerosene/kfe/application/transaction/KfeSubmitTransactionUseCase.java
src/main/java/com/kerosene/kfe/service/KfeExecutionOutboxService.java
src/main/java/com/kerosene/kfe/service/KfeChannelLifecycleService.java
src/main/java/com/kerosene/kfe/service/KfeChannelCapacityWorker.java
src/main/java/com/kerosene/kfe/service/KfeChannelRebalanceWorker.java
src/main/java/com/kerosene/kfe/service/KfePsbtWorkflowService.java
src/main/java/com/kerosene/kfe/service/KfeVaultMeshDayRotationWorker.java
src/test/java/com/kerosene/kfe/maintenance/KfeMaintenanceServiceTest.java
src/test/java/com/kerosene/kfe/maintenance/JdbcKfeMaintenanceStoreTest.java
src/test/java/com/kerosene/kfe/maintenance/KfeMaintenancePostgresTest.java
src/test/java/com/kerosene/kfe/controller/KfeMaintenanceAdminControllerTest.java
src/test/java/com/kerosene/kfe/application/transaction/KfeSubmitTransactionUseCaseTest.java
src/test/java/com/kerosene/kfe/service/KfeExecutionOutboxServiceTest.java
src/test/java/com/kerosene/kfe/maintenance/KfeMaintenanceAdmissionCoverageTest.java
docs/operations/cell-maintenance.md
```

Coordinator alone owns migration/build/settings changes and runs Gradle.
Worker must send the exact proposed SQL for coordinator application before
claiming persistence validated. No shared Gradle execution or deployment.
Additional mutation paths need a manifest amendment before editing.

## 2026-10-02 wave amendment

The coordinator restores the exact committed pricing/quorum facade algorithms
from `6b287b1`, before the incomplete extraction at this baseline. No primary
checkout files or financial algorithms are rewritten or invented.
A dedicated worker may add only `KfeMaintenanceHttpBarrier.java`, its test and
`docs/operations/maintenance-entrypoints.md`. It inventories remaining roots,
keeps all unknown-coverage blockers, and does not run shared Gradle, modify
migrations/build/settings/security configuration or existing worker patches.
Integration and every build are coordinator-owned.
The coordinator also adapts the four pre-existing unit-test suites for channel
lifecycle, rebalance, PSBT and day rotation to explicitly supply a mocked active
store through the real maintenance service. Production unavailable defaults
remain unchanged; the test-only fixture does not assert coverage or readiness.
Coordinator owns a V58 continuation extension to Guard/Store/Service/JdbcStore
and its unit/PostgreSQL tests. API: scheduleContinuation(operation, Executor,
Runnable) requires a current admitted parent, captures durable child provenance
before enqueue, activates only after proven parent commit, cancels only proven
unstarted work on rollback, claims once, and never expires unresolved children.
No consumer may treat this extension alone as complete callback coverage.
Coordinator integrates the HTTP hook only in the standalone security chain,
fixes JWT exception scoping so downstream errors are not misreported as session
failures, and owns runtime integration tests and pinned CI dependency references.
Embedded host chains remain a coverage blocker until independently integrated.
After HTTP handoff, that worker owns only KfePaymentRequestService.java, its
existing service test, a new KfePaymentRequestMaintenanceTest.java and
docs/operations/payment-request-maintenance.md. Guard creation/explicit changes
and expiry-on-read after capability resolution; preserve anonymous pure lookup.
It must not edit shared maintenance API/store/migration, security or other tests.
Coordinator owns KfeWebhookDeliveryService.java and its new unit test, integrating
the continuation API before enqueue; retries/serialization/interruption failure
remain UNCERTAIN, never completed merely because the parent transaction committed.
After payment-request handoff, that worker owns only KfeWalletService.java,
KfeWalletNetworkService.java, their exact existing tests, a new
KfeWalletMaintenanceTest.java and docs/operations/wallet-maintenance.md. Preserve
financial algorithms and identity checks while guarding first effects; no
automatic signer activation/resume or reduction of unknown coverage blockers.

Additional disjoint workers own exact channel-producer/retention and notification
outbox boundaries, respectively, with their existing tests plus one new maintenance
test and runbook each. Coordinator owns transaction cancellation and all shared
guard/store/schema/status-count integration. No worker runs Gradle or writes Git.

## Continuation wave — October 2

Coordinator owns the payment-request Lightning/onchain monitors, cursor admission,
shared API/status integration and all KFE Gradle execution. A bounded worker owns
only KfeOnchainBalanceSyncService.java, its existing ApplyObservedTest, a new
maintenance/KfeBalanceObservationMaintenanceTest.java and its runbook. Another
owns only KfeBitcoinRuntimeBootstrap.java, KfeTaxEventService.java, their exact
existing tests, a new maintenance/KfeBootstrapTaxMaintenanceTest.java and its
runbook. No worker edits shared guard/schema/financial algorithms or clears
coverage blockers. Missing injection remains unavailable, remote/after-commit
uncertainty remains unresolved, and bootstrap during drain must preserve the
ADMIN surface without starting new wallet/RPC mutations.

Disjoint follow-on workers own (1) KfeCustodialDepositObservationService and its
existing test plus new maintenance tests/runbook, (2) KfeColdWalletObservationService
and its existing test plus new maintenance tests/runbook, and (3) the Dashboard,
BalanceEvent and TransactionEvent publishers with their exact tests and a new
maintenance test/runbook. Each must preserve financial algorithms, admit before
effects, and retain remote/after-commit uncertainty. Publisher callbacks must
capture a V58 child before enqueue, not admit fresh after parent commit. Main
owns all integration/Gradle/status changes; no shared-file edits by those workers.

A sixth worker owns only KfeInboundSettlementService, KfeNetworkMonitor and
KfeOutboundConfirmationMonitor with their exact tests plus a new maintenance
test/runbook. Protect their roots before ledger, managed-entity and provider
effects, retain all unresolved proof/provider/callback uncertainty. Coordinator
owns payment monitor proxy/cursor tests, address/key/statement/peer roots and
execution workflow integration. No worker runs shared Gradle or writes Git.

The coordinator authorizes the balance-observation worker's new disjoint scope:
only src/main/java/com/kerosene/kfe/service/KfeExecutionOutboxWorker.java,
KfeExecutionOutboxProcessor.java and KfeExecutionOutboxService.java in that same
directory; their exact existing KfeExecutionOutboxServiceTest.java,
KfeExecutionOutboxProcessorTest.java and KfeExecutionOutboxProcessorAdditionalTest.java
under src/test/java/com/kerosene/kfe/service/; new
src/test/java/com/kerosene/kfe/maintenance/KfeExecutionOutboxMaintenanceTest.java;
and docs/operations/execution-outbox-maintenance.md. Guard the entire worker batch
through processor execution, reject fresh processing/heartbeat during drain even
with an outbox lease token, retain nonempty-claim/remote uncertainty, and preserve
claim/lease/financial policies. This entry is the worker's only execution-plan
edit. Main owns helper/prepared services, shared guard, integration and Gradle;
this worker runs no Gradle, writes no Git and removes no coverage blocker.

After custodial-observation handoff, that worker owns only
KfeExecutionTransactionHelper.java, its exact existing test, a new
maintenance/KfeExecutionHelperMaintenanceTest.java and its runbook. Guard all
public helper mutation roots and replace detached after-commit threads with
durable V58 children captured before enqueue/commit. No financial algorithm
changes, shared guard/schema/status edits, Gradle or Git by that worker.

Coordinator alone owns the additional ReceiveAddressIssuer, MpcKeyService,
SystemWalletService, StatementService, PreparedExecutionService and
PlatformPeerInboundService roots, exact existing tests, new address/key/statement
and prepared/peer maintenance suites and the combined runbook. Publisher and
bootstrap workers subsequently perform read-only coverage/embedded-host audits;
they cannot clear blockers or mutate other owners' files. Those read-only audits
were interrupted by account limits, so no audit conclusion is inferred. Main has
taken over all returned/interrupted files, completing custodial notification V58
integration and owning the two direct outbound rail executors and their new suite.
Main also owns the real PostgreSQL transaction-publisher integration tests.
Main extends its bounded scope to KfeBitcoinZmqWatcher callback dispatch and
KfeColdWalletReactiveRefreshService, their new service-package maintenance tests
and reactive-refresh runbook. Admit before sequence/refresh effects or pending
target consumption; preserve hints on rejected admission, without claiming
durable source replay or introducing real sockets/signers in verification.

## Continuation wave — October 3

All earlier workers remain closed. The coordinator alone owns
KfeBalanceService.java, KfeDerivationCursorService.java, their new bounded
maintenance tests and disposable PostgreSQL participant integration tests,
the participant runbook, inventory and verification/status updates. Admit before
locking, hashing or dirtying managed state. Preserve balance/reorg/derivation
algorithms and existing transaction propagation; a direct invocation without
observable transaction completion must remain uncertain. A returned locked
mutable balance and remote/publication outcomes are not completion proof.
No coverage blocker, deployment gate or real custody authority is removed.

Two new disjoint workers may operate in this isolated checkout only. Fee/movement
worker owns KfeFeeSettlementService.java, application/transaction/
KfeBalanceMovementRecorder.java, their exact existing tests, a new
maintenance/KfeFeeMovementMaintenanceTest.java and
docs/operations/fee-movement-maintenance.md. Liquidity/audit worker owns
KfeLightningLiquidityService.java, KfeAuditLogService.java, their exact existing
tests, a new maintenance/KfeLiquidityAuditMaintenanceTest.java and
docs/operations/liquidity-audit-maintenance.md. Admit before locks, managed state,
ledger/audit writes and side-effecting breaker evaluations. Preserve algorithms,
idempotency, REQUIRED/REQUIRES_NEW contracts and pure observations. Both keep
remote/caught-error/direct-no-transaction uncertainty. Neither edits shared API,
schema, status/inventory/build or another owner's files, runs Gradle, writes Git,
deploys or removes blockers. Coordinator continues real JPA/PostgreSQL tests,
integration, docs indexing and all verification while they implement these leaves.

A third disjoint worker owns application/transaction/KfeTransactionStateMachine,
KfeTransactionIdempotencyUseCase, KfeTransactionOutboxUseCase and
KfeInternalPaymentRequestSettlementUseCase (Java), their exact existing tests,
new maintenance/KfeTransactionParticipantMaintenanceTest.java and
docs/operations/transaction-participant-maintenance.md. Protect state transitions,
idempotency reserve/complete, outbox production and internal request lock/markPaid
before first effects. Preserve pure lookup/hash methods, existing propagation and
financial algorithms; returned managed capabilities and outputs remain uncertain.
Same no-Gradle/no-Git/no-shared-files/no-deploy restrictions as the other workers.

After fee/movement and liquidity/audit handoff those two workers are closed; main
owns their integration and bounded runbook acceptance notes. Main extends only
the full-schema PostgreSQL suite to actual audit REQUIRED/REQUIRES_NEW suspension,
while the transaction-participant worker retains its independent write scope.
Final handoff: all three October 3 workers are closed. Coordinator owns every
returned file, integration repair, the full build and accepted evidence. No
worker's static handoff is treated as executed verification or update permission.
Coordinator additionally owns KfeMaintenanceService and its exact service test:
observe nested transaction completion before root resolution, retain caught inner
rollback/commit-failure uncertainty, and refuse nested unobservable transactions
before effects. No API/schema/financial algorithm change or blocker removal.

## Upstream and direct-provider continuation — October 3

Coordinator owns BinarySettlementGate.java, KfeQuorumGateway.java, their exact
existing tests, a new settlement-package maintenance suite, inventory/status and
all Gradle verification. Admission precedes evaluation, locking, consensus, audit
and signals; preserve gate ordering and caller transaction propagation. All
remote/returned-capability outcomes remain uncertain; no coverage gate is removed.
A disjoint provider worker owns integration/VaultMeshFinancialQuorumAdapter.java,
KfeVaultMeshMpcKeyAdapter.java, their exact existing tests and a new integration/
KfeVaultProviderMaintenanceTest.java plus its runbook. Another owns integration/
KfeRemoteFinancialTransactionApprovalClient.java and
KfeRemoteFinancialNotificationClient.java, their exact existing tests and a new
integration/KfeRemoteEffectsMaintenanceTest.java plus its runbook. Both read the
repository agent instructions, edit only those files, preserve provider contracts,
and admit before transport/key/consensus effects with conservative completion.
Neither runs Gradle, writes Git, changes shared maintenance APIs or deploys.
Coordinator additionally adapts the exact KfeNotificationMaintenanceTest real
remote-client fixture to inject the same admitted parent guard; its default
unavailable production behavior must not be bypassed in integration tests.
Coordinator extends KfeMaintenanceFinancialSchemaTest with bounded real-store
quorum admission/commit/drain/recreated-store cases; the quorum port is mocked,
so these establish durable uncertainty, not actual financial consensus.
Both provider workers are now closed. Coordinator owns all returned files,
fixture integration, acceptance and final build. A worker cached-classpath run
does not substitute for the pinned Gradle/full-schema verification.

## Diagnostic continuation — October 3

Coordinator owns a new maintenance/KfeMaintenanceAdmissionQuery.java and its
unit tests, bounded full-schema PostgreSQL diagnostic cases, HTTP barrier and its
exact tests, inventory/status/API/runbook integration and all Gradle/Git work.
Read-only query API: Page page(int limit, String cursor), limit 1..100, opaque
keyset cursor, unresolved rows only, coherent per-page snapshot, no resolution.
Page fields: schema, observedAt, mode, changeId, revision, diagnosticOnly=true,
entries and nextCursor; Entry fields: id, operation, admittedRevision, state,
admittedAt, parentAdmissionId. No credentials, financial payloads or clear tokens.
A bounded worker owns only controller/KfeMaintenanceAdmissionsAdminController.java
and controller/KfeMaintenanceAdmissionsAdminControllerTest.java. GET/HEAD exact
/api/admin/kfe/maintenance/admissions uses query.page(default limit=50,cursor=null),
positive authenticated ROLE_ADMIN before query, Cache-Control:no-store, fixed
400/503 error mapping. No writes or shared guard/store edits. Another worker is
read-only: trace outbound conflict-notification payload and actual consumers,
recommend a contract-compatible repair with evidence; do not edit financial code.
Both read repository instructions, use isolated checkout, run no Gradle/Git/deploy
and remove no coverage blocker. No resolution is permitted from admission IDs.
Both diagnostic workers are closed. Main owns returned controller files and
verification; the read-only audit is not delivery or runtime qualification.

## Invariants and verification

Drain/admission serialize on one durable row; admissions outlive process
crashes and transaction rollback must not manufacture completed execution.
Expiry does not mean safe. All PROCESSING/UNKNOWN/reconciliation uncertainties
block. Audit and state transitions are atomic. No signer activation.
Coordinator validates focused tests, then checks, then disposable PostgreSQL,
and inspects SQL and all guard integration points before any completion claim.
