# KFE Cell maintenance

Contract: `kerosene.kfe-maintenance/v1`. The ADMIN endpoints are
`POST /api/admin/kfe/maintenance/drain`, `POST /api/admin/kfe/maintenance/resume`,
and `GET /api/admin/kfe/maintenance/status`. Commands contain `changeId`,
`reason`, and `expectedRevision`; operator identity comes from authentication.

## Coordinator-owned SQL

Apply the following SQL in a coordinator-owned migration before starting the
runtime or running persistence tests. The worker does not edit migrations.
No new dependency, build, settings, or Shared audit-event change is required.
The coordinator has applied this schema as `V57__kfe_maintenance_admission.sql`;
that migration is authoritative and the PostgreSQL tests load it directly.
V58 extends that table for durable continuations; the SQL block below describes
V57 only. Apply the actual versioned migrations, not this older copied DDL.

```sql
CREATE TABLE financial.kfe_maintenance_control (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('ACTIVE', 'DRAINING')),
    change_id VARCHAR(128),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    changed_at TIMESTAMPTZ NOT NULL,
    operator_id BIGINT,
    reason VARCHAR(512),
    CHECK (mode <> 'DRAINING' OR change_id IS NOT NULL)
);
INSERT INTO financial.kfe_maintenance_control
    (singleton_id, mode, revision, changed_at)
VALUES (1, 'ACTIVE', 0, CURRENT_TIMESTAMP);

CREATE TABLE financial.kfe_maintenance_audit (
    id UUID PRIMARY KEY,
    action VARCHAR(16) NOT NULL CHECK (action IN ('DRAIN', 'RESUME')),
    change_id VARCHAR(128) NOT NULL,
    operator_id BIGINT NOT NULL CHECK (operator_id > 0),
    reason VARCHAR(512) NOT NULL,
    expected_revision BIGINT NOT NULL CHECK (expected_revision >= 0),
    revision BIGINT NOT NULL CHECK (revision > 0),
    from_mode VARCHAR(16) NOT NULL CHECK (from_mode IN ('ACTIVE', 'DRAINING')),
    to_mode VARCHAR(16) NOT NULL CHECK (to_mode IN ('ACTIVE', 'DRAINING')),
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (change_id, action),
    UNIQUE (revision)
);

CREATE TABLE financial.kfe_maintenance_admissions (
    id UUID PRIMARY KEY,
    operation VARCHAR(128) NOT NULL,
    admitted_revision BIGINT NOT NULL CHECK (admitted_revision >= 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('IN_FLIGHT', 'UNCERTAIN', 'COMPLETED')),
    admitted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL))
);
CREATE INDEX idx_kfe_maintenance_admissions_unresolved
    ON financial.kfe_maintenance_admissions (state, admitted_at)
    WHERE state <> 'COMPLETED';
```

## Safety boundaries

The control row serializes operator transitions and durable execution admission.
Admissions commit independently of the financial transaction. Completion is
recorded only after successful transaction completion; failure/crash/rollback
leaves an unresolved admission. There is no expiry-based clearance or force-clear
endpoint. Nested work reuses the admission of an already admitted workflow.

Nested financial transactions now require active completion synchronization too.
Each nested transaction observes rollback/unknown completion before root resolution,
including REQUIRES_NEW commit failure caught by a successful outer caller. A root
admitted without an observed transaction cannot acquire commit proof merely by
starting one later. Joined local committed work keeps its original completion
contract. This repairs a reproduced real PostgreSQL case that previously marked
the parent COMPLETED after a caught inner commit rejection; it now stays UNCERTAIN.
No new propagation, schema, force-clear or release authority is introduced.

Draining pauses new guarded submissions, claims, channel executions, PSBT writes,
and Vault day rotation. An already admitted synchronous outbox workflow may finish
and remains counted; a claim token alone cannot authorize fresh execution.
Queued work is preserved; draining never cancels, releases reserves, or changes
financial consensus. Liveness and signer activation are untouched; a paused startup
bootstrap refuses readiness without aborting the ADMIN process. Actual management
routing to an unready pod still requires deployment qualification.

Status is an observation, not a distributed Cell-wide shutdown certificate.
It counts admissions, all PROCESSING claims (including expired claims), UNKNOWN,
queued/retryable work, pending transactions, reconciliation states, channel jobs,
mesh phases, PSBT work, and unrecognized persisted statuses. Missing schema or
failed observation returns an observation blocker with `safeToUpdate=false`.

The integrated wave also guards wallet/payment-request/cancellation starts,
expiry-on-read and UTXO scans, notification claims/status/delivery, channel queue
producers/orphan release and statement retention. Coverage remains explicitly
incomplete despite the additional guarded address/key/tax/bootstrap, observation,
inbound/confirmation/payment-monitor, statement/prepared/peer and direct executor
roots. Publishers, custodial deposit notifications and helper after-completion
work capture durable V58 children before commit/enqueue. Balance/cursor,
fee/movement, liquidity/audit and state/idempotency/outbox/internal-request
participants also have independent admission boundaries. Local cursor/genesis
commit and audit REQUIRED/REQUIRES_NEW behavior have bounded real JPA/PostgreSQL
tests; other provider/caller contracts are not inferred from those tests.
ZMQ/debounce producers,
all embedded participants and actual remote completion/recovery have not all
been proved. HTTP successes and remote best-effort results remain
UNCERTAIN, so these patches are not a usable complete-Cell drain certificate.
The three unknown coverage blockers stay nonzero.
Do not remove these blockers through a configuration flag. Additional hooks need
a coordinator-approved manifest amendment and inventory evidence.

Coordinator validation must include the concurrency/rollback/restart PostgreSQL
tests and integration tests for each guard consumer. No worker Gradle run or
commit is evidence of validation; commit only after coordinator acceptance.

## Persistence test environment

The complete KFE now compiles using the exact historical committed pricing and
quorum facades from `6b287b1`, preserving the algorithms before the incomplete
extraction. No dirty primary checkout or invented finance implementation was used.
Complete test and executable `bootJar` verification now run in addition to this
focused project. CI pins the actual Contracts/Shared repositories and enables
the dedicated disposable PostgreSQL tests rather than silently skipping them.

The coordinator runs `KfeMaintenancePostgresTest` only in an exclusive disposable
database whose name starts with `kfe_maintenance_test_`. Set
`KFE_MAINTENANCE_POSTGRES_DISPOSABLE=true`, `KFE_MAINTENANCE_POSTGRES_URL`,
`KFE_MAINTENANCE_POSTGRES_USER`, and `KFE_MAINTENANCE_POSTGRES_PASSWORD`.
Without explicit opt-in these tests are skipped. They read V57 from the classpath,
apply it if absent, and create minimal financial status fixtures in this dedicated
database. Those fixtures exercise maintenance SQL predicates, not the full ledger
schema or remote providers. They now load V58 if absent as well.

`KfeMaintenanceFinancialSchemaTest` separately runs the **entire real Flyway
migration chain through V58** and validates maintenance observations/transitions
against its actual financial tables. It requires
`KFE_MAINTENANCE_FULL_SCHEMA_DISPOSABLE=true` and
`KFE_MAINTENANCE_FULL_SCHEMA_URL`, with a database named
`kfe_maintenance_test_full_schema_*`. It uses the same synthetic credential
environment references and never runs Flyway clean. This proves schema integration,
not production-data migration/rollback or complete Cell recovery qualification.

## Durable asynchronous continuation (V58)

`scheduleContinuation(operation, Executor, Runnable)` requires an admitted current
workflow. Before scheduling, a short independent transaction persists a child
admission with parent ID and the original admission revision. There is no public
token API, transaction-ID bypass or cross-request thread-local reuse.

Without an active financial transaction the child is READY. With one it is
WAITING until proven commit, then READY before enqueue. Proven rollback cancels
only WAITING (unstarted, unclaimable) work. Unknown completion/crash retains WAITING.
The executor claims READY exactly once into IN_FLIGHT, even during DRAINING. Child
effects finish through the same guard; failure remains UNCERTAIN. Parent completion
does not complete the child. Recursive children retain durable ancestry. Expiry,
executor rejection, timeout and process restart never clear pending work.

Status also counts `continuationsWaiting`, `continuationsReady` and unknown
admission states. A direct executor on a transaction-bound completion thread is
not allowed to reuse a completed transaction: its READY child stays unresolved.
Use a genuinely asynchronous transaction-free executor thread. There is no generic
restart/replay runner or force-clear API; captured closures are not stored as code.

Webhook delivery now captures this child before after-commit scheduling. Retry
exhaustion, serialization and interruption do not manufacture completed delivery.
Other futures/stream/callback consumers still require integration and evidence.
The three coverage blockers are not removed by this API or by passing its tests.

## Proposed next ownership manifest (not activated)

This section preserves the earlier planning inventory, not current ownership.
The coordinator activated and integrated the bounded batches listed in
`docs/agents/CELL_OPERATIONS_EXECUTION_PLAN.md`; those agents are now closed.
Current implemented boundaries are documented in this runbook and the wallet,
payment-request, producer and notification maintenance runbooks. Entries below
do not claim that remaining files were implemented or qualified.

This read-only inventory covers HTTP controllers, scheduled methods, stream/ZMQ
callbacks, transaction synchronizations, futures, and inbound integration ports.
The following is the concrete proposed amendment. Paths are relative to this KFE
worktree. Existing ownership of the maintenance files/tests/runbook must be retained.
No file below is authorized for editing until the coordinator activates it.

Immediate test-fixture amendments needed for the current patch:

```text
src/test/java/com/kerosene/kfe/service/KfeChannelLifecycleServiceMeshInjectTest.java
src/test/java/com/kerosene/kfe/service/KfeChannelRebalanceWorkerTest.java
src/test/java/com/kerosene/kfe/service/KfePsbtWorkflowServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeVaultMeshDayRotationWorkerTest.java
```

Inject `KfeMaintenanceService` backed by a mock store returning durable admission
records for ACTIVE tests; do not install a production no-op or change constructors
to bypass admission. Current owned submit/outbox tests already use this fixture.

Production amendment:

```text
src/main/java/com/kerosene/kfe/service/KfeWalletService.java
src/main/java/com/kerosene/kfe/service/KfeWalletNetworkService.java
src/main/java/com/kerosene/kfe/service/KfeReceiveAddressIssuer.java
src/main/java/com/kerosene/kfe/service/KfeMpcKeyService.java
src/main/java/com/kerosene/kfe/service/KfeSystemWalletService.java
src/main/java/com/kerosene/kfe/service/KfePaymentRequestService.java
src/main/java/com/kerosene/kfe/service/KfeTransactionCancellationService.java
src/main/java/com/kerosene/kfe/service/KfeTaxEventService.java
src/main/java/com/kerosene/kfe/service/KfeChannelCapacityController.java
src/main/java/com/kerosene/kfe/service/KfeChannelCapacityQueueService.java
src/main/java/com/kerosene/kfe/service/KfeChannelRebalanceQueueService.java
src/main/java/com/kerosene/kfe/service/KfeChannelDrainMonitor.java
src/main/java/com/kerosene/kfe/service/KfeChannelMeshInjectReconciler.java
src/main/java/com/kerosene/kfe/service/KfeOnchainBalanceSyncService.java
src/main/java/com/kerosene/kfe/service/KfeCustodialDepositObservationService.java
src/main/java/com/kerosene/kfe/service/KfeColdWalletObservationService.java
src/main/java/com/kerosene/kfe/service/KfeOutboundConfirmationMonitor.java
src/main/java/com/kerosene/kfe/service/KfePaymentRequestOnchainMonitor.java
src/main/java/com/kerosene/kfe/service/KfePaymentRequestLightningMonitor.java
src/main/java/com/kerosene/kfe/service/KfeNetworkMonitor.java
src/main/java/com/kerosene/kfe/service/KfeInboundSettlementService.java
src/main/java/com/kerosene/kfe/service/KfePlatformPeerInboundService.java
src/main/java/com/kerosene/kfe/service/KfeColdWalletReactiveRefreshService.java
src/main/java/com/kerosene/kfe/service/KfeBitcoinZmqWatcher.java
src/main/java/com/kerosene/kfe/service/KfeExecutionOutboxWorker.java
src/main/java/com/kerosene/kfe/service/KfeExecutionOutboxProcessor.java
src/main/java/com/kerosene/kfe/service/KfeExecutionTransactionHelper.java
src/main/java/com/kerosene/kfe/service/KfePreparedExecutionService.java
src/main/java/com/kerosene/kfe/service/KfeFinancialNotificationOutboxService.java
src/main/java/com/kerosene/kfe/service/KfeNotificationOutboxWorker.java
src/main/java/com/kerosene/kfe/service/KfeNotificationOutboxProcessor.java
src/main/java/com/kerosene/kfe/webhook/KfeWebhookDeliveryService.java
src/main/java/com/kerosene/kfe/service/KfeDashboardPublisher.java
src/main/java/com/kerosene/kfe/service/BalanceEventPublisher.java
src/main/java/com/kerosene/kfe/service/TransactionEventPublisher.java
src/main/java/com/kerosene/kfe/service/KfeStatementService.java
src/main/java/com/kerosene/kfe/service/KfeStatementRetentionService.java
src/main/java/com/kerosene/kfe/runtime/KfeBitcoinRuntimeBootstrap.java
src/main/java/com/kerosene/kfe/maintenance/KfeMaintenanceCoverageInventory.java
```

Suggested division into sequential integration batches:

| Boundary | Required behavior |
| --- | --- |
| Wallet, address, key generation, tax, cancellation | Admit before DB/provider effects; preserve already admitted compensation and post-commit completion. Wallet provisioning adapter delegates to the guarded wallet service. |
| Payment-request create/expire/hide/cancel and GET/list/publicGet | Guard expiry-on-read. A read that requires no mutation may remain available; a mutation needs admission before writing. |
| Channel producers/queue APIs/orphan release | Freeze new enqueue/claim starts before job-state changes. Already admitted channel work may compensate/commit without a second ACTIVE check. |
| Polling, LND stream, ZMQ, debounced observers | Admit each mutating callback before effects/cursor advancement. Pause new work without falsely acknowledging or discarding pending events. Resume reconciles from durable provider data. |
| Execution processor/helper/prepared execution | Validate durable claim/admission provenance for continuation during drain; do not block completion of an existing claim or admit unrelated work by transaction ID alone. |
| Notification claims, delivery, webhooks, dashboard/statement callbacks | Persist pending continuation admission before enqueue/afterCommit scheduling; complete it only after actual delivery/local completion and successful transaction completion. |
| Statement cleanup | Pause newly started purge; track a running purge until its transaction finishes. |
| Startup bootstrap | Guard system-wallet creation and RPC wallet loading. Restart while DRAINING must retain the admin status/resume surface and skip new bootstrap mutations instead of aborting startup. |

Confirmed benign reads that do not need new production edits include transaction
history/quote, audit roots/history, aggregate provider health/operations metrics,
wallet listing/names, reserve overview, descriptor/index reads, and pure raw-tx
parsing. The balance reconciliation scheduler indirectly writes through guarded
sync/observation services; its logs/metrics-only checks can remain active.
Leaf ledger/audit/idempotency/derivation methods remain internal participants of
an admitted root, not independent new executions. Verify every caller, including
embedded-runtime consumers, before treating that classification as complete.

### Contract amendment needed for async continuation

The current thread-local nesting covers synchronous work only. Add a durable
continuation token captured while the parent admission is unresolved, before
crossing a thread or after-completion boundary. A captured child may finish in
DRAINING without creating a new root admission; arbitrary callbacks cannot use
the exception. Store child provenance and claim/completion state, and count
pending/callback/uncertain children in status. Rollback discards unexecuted local
callbacks only when their cancellation is proved; crash/timeout cannot clear them.
The coordinator must freeze exact token signatures and own any V58 schema change
before consumers are edited. No signer activation or financial algorithm change.

Replace the three coverage blockers with inventory-derived values only after
every HTTP, scheduled, stream, callback, future, and embedded-port boundary is
classified and tested. Unknown persisted payment-request/wallet/notification or
callback states must produce named blockers. Keep `safeToUpdate=false` until this
evidence exists; do not claim the current partial inventory is certification.

The follow-on test amendment retains all current owned tests and the four
immediate fixture amendments above, plus these exact paths. Tests absent from
the worktree are proposed new files; there is no directory-wide write grant.

```text
src/test/java/com/kerosene/kfe/service/KfeWalletServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeWalletNetworkServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeReceiveAddressIssuerTest.java
src/test/java/com/kerosene/kfe/service/KfeMpcKeyServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeSystemWalletServiceTest.java
src/test/java/com/kerosene/kfe/service/KfePaymentRequestServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeTransactionCancellationServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeTaxEventServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeChannelCapacityControllerTest.java
src/test/java/com/kerosene/kfe/service/KfeChannelCapacityQueueServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeChannelRebalanceQueueServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeChannelDrainMonitorTest.java
src/test/java/com/kerosene/kfe/service/KfeChannelMeshInjectReconcilerTest.java
src/test/java/com/kerosene/kfe/service/KfeOnchainBalanceSyncServiceApplyObservedTest.java
src/test/java/com/kerosene/kfe/service/KfeCustodialDepositObservationServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeColdWalletObservationServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeOutboundConfirmationMonitorTest.java
src/test/java/com/kerosene/kfe/service/KfePaymentRequestOnchainMonitorTest.java
src/test/java/com/kerosene/kfe/service/KfePaymentRequestLightningMonitorTest.java
src/test/java/com/kerosene/kfe/service/KfeNetworkMonitorTest.java
src/test/java/com/kerosene/kfe/service/KfeInboundSettlementServiceTest.java
src/test/java/com/kerosene/kfe/service/KfePlatformPeerInboundServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeColdWalletReactiveRefreshServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeBitcoinZmqWatcherTest.java
src/test/java/com/kerosene/kfe/service/KfeExecutionOutboxProcessorTest.java
src/test/java/com/kerosene/kfe/service/KfeExecutionOutboxProcessorAdditionalTest.java
src/test/java/com/kerosene/kfe/service/KfeExecutionTransactionHelperTest.java
src/test/java/com/kerosene/kfe/service/KfePreparedExecutionServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeFinancialNotificationOutboxServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeNotificationOutboxProcessorTest.java
src/test/java/com/kerosene/kfe/webhook/KfeWebhookDeliveryServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeDashboardPublisherTest.java
src/test/java/com/kerosene/kfe/service/BalanceEventPublisherTest.java
src/test/java/com/kerosene/kfe/service/TransactionEventPublisherTest.java
src/test/java/com/kerosene/kfe/service/KfeStatementServiceTest.java
src/test/java/com/kerosene/kfe/service/KfeStatementRetentionServiceTest.java
src/test/java/com/kerosene/kfe/runtime/KfeBitcoinRuntimeBootstrapTest.java
src/test/java/com/kerosene/kfe/maintenance/KfeMaintenanceRuntimeCoverageTest.java
src/test/java/com/kerosene/kfe/maintenance/KfeMaintenanceContinuationPostgresTest.java
src/test/java/com/kerosene/kfe/maintenance/KfeMaintenanceReadMutationTest.java
```
