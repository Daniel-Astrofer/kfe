# Payment-request maintenance admission

This 2026-10-02 change covers only `KfePaymentRequestService`, its existing service
test, a new `KfePaymentRequestMaintenanceTest`, and this document. It does not edit
HTTP/security configuration, the shared guard/store, V57/V58, callback consumers,
or the broader entrypoint inventory. It is partial mutation coverage, not a
`safeToUpdate` certificate. All three unknown-coverage blockers remain unchanged.

## Injection and coordinator integration

The service preserves its existing constructor and uses the same mandatory
`@Autowired setMaintenanceGuard(KfeMaintenanceGuard)` pattern as the other guarded
services. Spring must inject the real `KfeMaintenanceService` backed by the durable
store. Without injection, `KfeMaintenanceGuard.unavailable()` rejects every new
mutation with 503. There is no optional/no-op production guard or ACTIVE default.
Unexpired public reads can still execute without mutation admission.

The existing `KfePaymentRequestServiceTest` was confirmed to exist. Its ACTIVE
fixture now explicitly supplies the real maintenance service with a mocked store
returning admission records. Its address, invoice, rail payload, settlement display,
and cancellation algorithm expectations are preserved.

No constructor or controller change is required. The coordinator's existing
maintenance exception advice maps the service's admission exception to HTTP 503
with schema `kerosene.kfe-maintenance/v1`. The HTTP barrier continues to exclude
the public route family; this service boundary closes its expiry-on-read write
path without introducing a JWT requirement for anonymous callers.

## Exact mutation boundaries

| Operation | Admission point | Completion evidence |
| --- | --- | --- |
| `payment-request.create` | After existing validation, owner-scoped wallet resolution, rail resolution and receiving-wallet checks; before deriving/issuing addresses, advancing a wallet index, issuing an invoice, or saving/auditing the request. | INTERNAL-only creation performs local writes and may complete after observed commit. Any ONCHAIN/LIGHTNING rail supplies certainty false because provider effects cannot be certified by a local response/commit. |
| `payment-request.expire` | After existing owner-scoped payment-request resolution, before calling `expire()` or saving/auditing. | Local entity/repository/audit work; the real guard observes outer transaction completion. Failure/rollback cannot certify completion. |
| `payment-request.hide` | After existing owner-scoped payment-request resolution, before calling `hide()` (including hiddenAt) or saving/auditing. | Same local transaction boundary as explicit expiry. |
| `payment-request.cancel` | Before delegating to the existing cancellation service, which retains its own owner lookup and idempotency. | Always uncertain: the delegate catches provider/compensation failures and schedules dashboard publication afterCommit; its returned entity cannot prove those effects finished. |
| `payment-request.expiry-on-read` | Only after `list/get/publicGet` resolve their owner/public-ID capability, the existing OPEN/deadline test succeeds and the existing settlement lookup finds no settlement. Before changing the managed entity or calling save. | This exact local expiry write may complete only through the real guard's transaction completion policy. There is no new audit, provider cancellation or callback added to expiry-on-read. |

Names are fixed and bounded; public IDs, hashes, invoices and user identifiers are
not incorporated into admission names. No `guard.status()` check decides admission;
the durable store serializes root admission against drain.

An admission rejection occurs before any managed status/index/timestamp change,
not merely before `repository.save`. This matters because JPA can flush dirty
managed entities without an explicit save call. A new direct service root is
rejected while DRAINING. Synchronous calls nested inside an already admitted
workflow reuse that workflow and may finish after drain starts. Once it exits,
the next call must admit independently; no service-local thread/request token is
introduced.

## Anonymous lookup behavior and preserved financial rules

The actual public routes are
`GET /api/public/kfe/payment-requests/{publicId}` and
`GET /api/public/kfe/payment-requests/lookup?invoice=...`.
The first delegates directly to `publicGet`. The second calls the unchanged
`KfePlatformLightningPolicy.resolvePlatformInvoice`, which resolves BOLT11/raw
invoice/payment hash using the existing repository/classifier logic, then calls
`publicGet(entity.getPublicId())`. The public-ID resolution inside `publicGet`
therefore still succeeds before its expiry write may request admission.

The resolved public ID is the existing capability for this public operation;
the service does not add authentication, change invoice classification, or admit
unknown capability attempts. An unknown public ID or owner-scoped request still
raises the existing not-found exception without calling the guard. A pure lookup
is available anonymously in ACTIVE and DRAINING modes. An overdue OPEN request
without settlement needs expiry admission: ACTIVE preserves expiry-before-return;
DRAINING returns 503 and leaves status OPEN with no save. It does not silently
return a newly expired response, hide a financial transition, or cancel an invoice.

The original `isExpired` test requires OPEN plus a non-null UTC deadline before
now. Existing PAID/EXPIRED/HIDDEN/CANCELLED/FAILED reads remain observational.
Requests with no deadline or a future deadline remain observational. Due OPEN
requests with an existing settlement remain OPEN as before. Settlement lookup
retains its precedence: use `paidTransactionId` when present; otherwise use the
existing `payment-request:{id}:` idempotency prefix. There is no new fallback or
settlement-status reinterpretation.

Explicit expire retains its separate rule: change OPEN requests regardless of
the deadline/settlement-on-read rule. Explicit hide still changes every status
except PAID and retains its existing repeated-hide timestamp/audit behavior.
Both explicit command roots request admission even when their original state
condition ultimately makes them a no-op. Cancellation's already-terminal return,
related-transaction selection, reserve/liquidity handling, invoice best effort
and dashboard behavior are unchanged inside the admitted boundary.

Create retains public-ID generation, receiving derivation/index increments,
multi-rail order, invoice payload/TTL handling, fixed/open amount behavior,
webhook URL storage, audits and DTO fields. This service stores webhook metadata;
it does not directly schedule webhook delivery. Invalid creation validation and
owner/receiving-wallet resolution still happen before admission.

## Continuations and remaining coverage

The coordinator's `scheduleContinuation(String operation, Executor executor,
Runnable work)` API was read along with its real service/store implementation.
It requires a currently admitted parent, captures durable child provenance before
enqueue, releases committed WAITING work, cancels proven unstarted rollback work,
and claims children once. Those shared files and V58 are unchanged by this task.

No direct asynchronous enqueue exists in the owned payment-request service.
Adding a wrapper child around the cancellation delegate would not prove the
delegate's internally scheduled callback completed. It would also change the
timing of cancellation. Instead, cancellation's admission stays uncertain until
the actual callback/compensation consumers have an audited completion boundary.
Remote address/invoice creation also remains conservative. This can leave durable
UNCERTAIN admissions even after a successful response; this patch has no force
clear or expiry-based clearance.

Concrete remaining paths include:

- `KfeTransactionCancellationService.cancelTransaction` and direct
  `cancelPaymentRequest` callers outside this wrapper can reach the same financial
  changes. The transaction HTTP perimeter covers its integrated HTTP roots, but
  direct/embedded roots still need independent admission evidence.
- `KfePaymentRequestLightningMonitor` polling, invoice-stream handling,
  `expireRequest`, settlement/failure/reconciliation methods mutate requests
  directly; they do not call this service's expiry helper.
- `KfePaymentRequestOnchainMonitor` polling/observation/settlement and
  `KfeInternalPaymentRequestSettlementUseCase.markPaid` write through the
  repository outside this service. Their roots/provenance must be audited.
- `KfeDashboardPublisher.publishAfterCommit`, statement/notification publication
  and webhook delivery need captured durable continuations at their actual
  scheduling boundaries; the presence of V58 alone does not cover them.
- Direct receiving-address, wallet, embedded finance and remote provider roots
  remain independent of this service's wrapping boundary.

This change preserves the existing repository/locking strategy. It does not add
pessimistic locks, change settlement racing semantics, introduce optimistic
versions, or claim that duplicate snapshots in different transactions are now
serialized. The maintenance admission/drain race is delegated to the durable
store. A list with multiple due entries still runs within its existing outer
transaction; if a later admission fails, Spring rollback must prevent earlier
local writes from committing, and their bound admissions remain uncertain.

Keep `mutationCoverageUnknown=1`, `callbackCoverageUnknown=1`, and
`readSideEffectsUnknown=1`. The earlier HTTP inventory document remains untouched;
its public expiry gap description predates this narrowly scoped service patch.
This document supplies the new evidence without declaring the whole inventory
complete.

## Verification handoff

The new maintenance test uses the real payment-request service, real maintenance
service, real cancellation delegate, actual public controller/invoice policy and
mocked durable store/financial boundaries. It checks rejection before actual
entity transitions and repository/provider/audit/dashboard effects; owner and
public capability ordering; pure anonymous lookup in DRAINING; 503 for due public
GET/hash lookup; ACTIVE expiry/duplicate reads; settlement suppression/lookup
precedence; local commit/rollback resolution; conservative remote/cancel results;
synchronous nesting and next-call cleanup; and unchanged coverage blockers.

Deterministic admission interleavings cover drain beginning after capability
resolution and drain beginning after successful admission. They do not certify
PostgreSQL row locking or concurrent financial settlement. Transaction tests drive
the real guard's synchronization callback with a mocked store; they do not assert
that mock entities/repositories perform a database rollback. Controller tests use
standalone MVC without installing/changing a security chain; configured public
security integration remains coordinator-owned.

No Gradle/build/test execution is performed by this worker. Coordinator should run
`KfePaymentRequestServiceTest`, `KfePaymentRequestMaintenanceTest`, the existing
platform-invoice policy tests and full KFE suite, then verify these paths with real
Spring transaction proxies and the existing disposable PostgreSQL/Flyway setup.
Earlier coordinator schema/test validation is separate from validation of this
new service patch.
