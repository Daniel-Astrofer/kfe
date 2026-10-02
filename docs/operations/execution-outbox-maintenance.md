# Execution outbox admission

`KfeExecutionOutboxWorker`, `KfeExecutionOutboxProcessor` and
`KfeExecutionOutboxService` require the real injected maintenance guard. Each
mandatory setter rejects null; construction without injection stays unavailable.
Admission storage failure fails closed before queue/helper/provider effects.

The scheduled worker admits `outbox.worker-batch` before querying or claiming any
row, and retains that root through every synchronous processor and rail-executor
call in the batch. Admission rejection pauses the tick without claiming work or
reporting execution completed. An already-admitted batch may finish after drain
begins; the next independent tick requires fresh admission. The existing 100-item
claim limit, candidate policies and per-item exception handling remain unchanged.

`outbox.process` is admitted before lease heartbeat, preparation, executor
selection and provider execution. A valid outbox claim token fences ownership
only: it cannot establish maintenance provenance or authorize fresh processing
during DRAINING. Synchronous callers reuse the real guard's current workflow;
no status precheck, token-derived authority or permissive fallback is used.

`outbox.claim-due` and `outbox.claim-immediate` retain uncertainty for successful
nonempty claims. An empty result may complete, subject to proven transaction
commit; rollback or an exception cannot complete it. `outbox.heartbeat` explicitly
admits before DB mutation and remains uncertain even when it returns true or false:
a standalone lease extension proves neither continuation nor execution completion.
Invalid immediate/heartbeat inputs keep their existing effect-free returns.

Processor execution and nonempty worker batches always retain uncertainty.
Swallowed claim-loss/provider exceptions, UNKNOWN/retry/final markers, successful
broadcast or local financial persistence cannot retire that maintenance admission.
Existing lease duration, token fencing, failure classification, provider selection,
prepared payload handling and financial algorithms remain intact.

The coordinator also guards direct `KfeOnchainOutboundExecutor.execute` and
`KfeLightningOutboundExecutor.execute` calls before prepared-payload lookup,
provider liveness/preparation, signing/broadcast/payment or helper recording.
These mandatory injected guards default unavailable. Their pure `supports`
predicates remain readable. A prepared payload or outbox lease cannot authorize
fresh execution during drain; nested worker/processor calls reuse the admitted
workflow and conservatively retain uncertainty.

`KfeExecutionOutboxMaintenanceTest` uses the real worker, processor, claim service,
rail executors and guard with an explicit ACTIVE/DRAINING mock-store fixture.
Synchronous drain races demonstrate one admitted root through actual prepared
broadcast and recording. Tests cover fresh-token rejection, unavailable injection,
admission/queue/lease/helper/prepared-storage outages before provider effects,
swallowed failures, empty/nonempty completion and Spring commit/rollback. Existing
processor suites explicitly inject real test admission; their provider/failure
policy assertions remain in place. No Gradle or PostgreSQL execution was performed
by this worker; coordinator verification is required.

Direct helper/prepared/peer roots and helper V58 children are now integrated by
the coordinator in their separate runbooks. `KfeRailExecutorMaintenanceTest`
checks both real direct executors with mock provider/persistence boundaries,
including no-effects rejections and nested drain. Synchronous submit end-to-end
integration, other embedded producers, remote recovery and provider/callback
completion still require evidence. No shared guard, schema, release
authority or coverage blocker is changed; `mutationCoverageUnknown`,
`callbackCoverageUnknown` and `readSideEffectsUnknown` remain blockers.
