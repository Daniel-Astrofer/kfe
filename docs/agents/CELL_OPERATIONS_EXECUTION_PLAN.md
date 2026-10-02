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

## Invariants and verification

Drain/admission serialize on one durable row; admissions outlive process
crashes and transaction rollback must not manufacture completed execution.
Expiry does not mean safe. All PROCESSING/UNKNOWN/reconciliation uncertainties
block. Audit and state transitions are atomic. No signer activation.
Coordinator validates focused tests, then checks, then disposable PostgreSQL,
and inspects SQL and all guard integration points before any completion claim.
