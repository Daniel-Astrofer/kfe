# Bitcoin bootstrap and tax classification maintenance

This boundary covers `KfeBitcoinRuntimeBootstrap.run` and
`KfeTaxEventService.classify`. It does not authorize Cell updates or certify
other callers of the system-wallet service. The shared maintenance service,
store, schema, financial algorithms and coverage blockers remain coordinator
owned. See [the maintenance contract](cell-maintenance.md) and
[entrypoint inventory](maintenance-entrypoints.md).

Both components require Spring injection of the real `KfeMaintenanceGuard`.
Compatibility construction defaults to `KfeMaintenanceGuard.unavailable()`;
it never grants admission. There is no ACTIVE fallback, signer activation,
automatic resume, or admission override.

## Startup boundary and recovery

`bitcoin.bootstrap` admits before `ensureSystemWallets`, network/sync probes,
and Bitcoin Core wallet loading/creation. A rejected admission, missing guard
in a directly constructed instance, or admission-store outage causes a clean
pause before any wallet or RPC call. The runner returns so the application
can retain its authenticated ADMIN status/resume surface. It does not report
system wallets ready or persist a successful bootstrap marker in this case.

Each runner invocation publishes `REFUSING_TRAFFIC` when an application event
publisher is available. The bootstrap component also listens for Boot's `ACCEPTING_TRAFFIC` readiness
transition and publishes `REFUSING_TRAFFIC` while bootstrap has not completed.
This listener is needed because Boot publishes its readiness transition after
`ApplicationReadyEvent` and after the runners return. A paused startup is
therefore alive for administration but not successfully bootstrapped. The
standard Spring event multicaster must remain synchronous; custom embedded
hosts or asynchronous event dispatch require independent integration evidence.

After fixing an admission-store outage or performing the existing authenticated
resume procedure, restart KFE to retry bootstrap through a fresh admission.
An embedding host can explicitly invoke the runner again through the same
guard. This component does not start a background retry loop or publish an
`ACCEPTING_TRAFFIC` event itself. Existing network mismatch, required-client,
required-sync and post-admission failures still propagate; a failure after
effects start is not caught and misrepresented as a clean maintenance pause.

The admitted workflow includes the synchronous system-wallet transaction and
all configured RPC wallet names, retaining the original ordering, trimming and
deduplication. Local-only success can resolve as completed after the observed
transaction commits (or after the proxied system-wallet transaction returns
when no outer transaction exists). Rollback, unknown transaction completion,
and exceptions cannot prove completion. Admission persistence failure before
work prevents all effects; resolution persistence failure leaves the shared
durable blocker unresolved.

RPC `ensureWalletLoaded` can load/create a wallet and recover a load failure
using a subsequent read. Any workflow that invokes it resolves as UNCERTAIN,
including a successful return and a later local commit. Wallet-loading success
is not durable proof of remote completion. A successful bootstrap may allow
normal application readiness while that maintenance admission remains a
blocker; readiness is not `safeToUpdate`. No expiry or retry clears remote
uncertainty in this component.

## Tax boundary and benign reads

`classify` keeps its input normalization, event-ID parsing and
`findByIdAndUserId` ownership lookup before admission. Failure to find that
user's transaction retains the existing not-found result. `tax-event.classify`
admits before classification lookup, entity setters, repository save, or JPA
dirty checking could change a managed classification. Both creating and
updating a classification use this boundary. A successful classification is
completed only after the enclosing transaction is known to have committed;
rollback, unknown completion and save errors remain unresolved.

`list` and JSON/CSV `export` are benign user-scoped reads and do not request
admission. They remain usable when draining or admission storage is unavailable,
provided their own data repositories are available. Event type, default
classification, quantity, source-reference precedence, wallet mapping,
retention hint, educational notice and export algorithms are unchanged.

Both boundaries reuse an already admitted synchronous parent workflow during
drain. Nested work cannot create another root admission or erase uncertainty
from a caught nested failure. After the parent returns, a separate invocation
needs fresh admission. This is not async continuation or callback provenance.

## Verification and remaining evidence

`KfeBootstrapTaxMaintenanceTest` uses the actual `KfeMaintenanceService` with a
mock store for DRAINING, uninjected construction, admission storage outage,
benign tax reads, ownership/input checks, commit/rollback/unknown completion,
remote success/failure and nested in-flight work. Its minimal Spring startup
fixture exercises required guard injection, the actual Boot readiness event
sequence, and an accessible authenticated ADMIN controller bean during pause.
It does not claim full HTTP server/security-chain or persistence certification.
`KfeBitcoinRuntimeBootstrapTest` explicitly injects the real service backed by
the existing active mock-store fixture to retain network/sync regression tests.
There is no existing `KfeTaxEventServiceTest` at this baseline.

Worker evidence, 2026-10-02: isolated `javac` compilation and JUnit Platform
execution of these two classes passed all 24 test invocations. Dependencies and
unmodified application classes came from existing build artifacts/cache;
verification outputs were isolated under a temporary directory. No shared
Gradle process ran and no shared build outputs were modified. This is
focused unit/startup evidence, not a full build, HTTP integration or database
qualification.

Coordinator verification included these two classes in the passing complete
Gradle check/bootJar (see [STATUS](../STATUS.md)). Workers did not run
shared Gradle or mutate Git, production, deployment, migrations or status.
The static `mutationCoverageUnknown`, `callbackCoverageUnknown` and
`readSideEffectsUnknown` blockers are untouched. Direct system-wallet creation is
now separately guarded by the coordinator. Embedded ADMIN/security integration,
management routing to unready pods and unresolved remote effects remain
separate evidence requirements; this bounded patch cannot clear them.
