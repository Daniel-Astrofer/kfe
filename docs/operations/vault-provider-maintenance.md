# Direct Vault quorum and MPC provider admission

## Scope

`VaultMeshFinancialQuorumAdapter` and `KfeVaultMeshMpcKeyAdapter` now admit
direct provider operations through `KfeMaintenanceGuard`. Both retain their
existing constructors, initialize the guard to `KfeMaintenanceGuard.unavailable()`
and require Spring setter injection via `@Autowired`. Passing null to either
setter throws before replacing the current guard.

| Entry point | Operation | First protected effect |
| --- | --- | --- |
| `requireThresholdConsensus` | `vault.quorum.threshold` | Member USERS group-key HTTP reads, before the coordinator consensus POST |
| `requireHealthyUnanimousConsensus` | `vault.quorum.legacy` | Coordinator constitution-context HTTP read, before threshold consensus |
| `keygenWallet` | `vault.mpc.keygen` | `ObjectProvider.getIfAvailable()`, before USERS deposit-key retrieval |

Null/expired quorum proposals and malformed legacy proposal hashes still fail
pure validation before admission. Hashing and constructor configuration retain
their existing behavior. MPC resolution retains the shared USERS key model,
x-only/output-key fallback, validation and logging. Quorum request fields,
member agreement, threshold policy, proof verification and attribution are
unchanged.

## Drain and uncertain outcomes

Fresh calls during drain, without injected admission, or during an admission
storage failure stop before the protected effects. The legacy quorum entrypoint
admits before its context request; its nested threshold call participates in the
same workflow when the real maintenance service is injected.

All three roots use a completion predicate that always returns false. Returning
a key or an accepted, verified quorum decision is not proof of completion of the
financial workflow or recovery of remote effects. Provider/transport exceptions,
caught member-read failures, invalid responses and unresolved storage outcomes
must remain blockers. A synchronous child of an already admitted workflow may
run during drain according to the existing guard contract; it makes the parent
uncertain even if the parent otherwise returns successfully.

Use the existing authenticated maintenance drain/status workflow. These adapters
provide no override, automatic signer activation, resume or blocker-clearing
mechanism. Do not treat a successful reply as permission to resolve an uncertain
admission or update the Cell. Unknown mutation, callback and read-side-effect
coverage blockers remain in force.

## Bounded verification evidence — 2026-10-03

The following sources were compiled with Java 21 `javac` into
`/tmp/kfe-vault-admission.ty3dCX`: the two adapters, their existing MPC test,
the new provider maintenance suite, and current guard/store/service plus
`MaintenanceTestFixture` sources read without edits. Other dependencies came
from existing checkout class outputs and locally cached jars, selecting one
cached version per artifact. JUnit Platform was launched through JShell.

The worker's cached-classpath focused run executed 25 tests: 20 cases in
`KfeVaultProviderMaintenanceTest` and five in `KfeVaultMeshMpcKeyAdapterTest`.
All 25 passed, with no skipped or aborted cases. Existing MPC tests explicitly
inject `MaintenanceTestFixture.active()`; new maintenance cases use the real
`KfeMaintenanceService` with a synthetic store and mocked provider/transport.
The new suite exercises missing injection, drain and admission-storage failure
before effects for every root; null setters and pure invalid-input rejection;
ACTIVE success/error uncertainty; GET/group-key and POST failures; legacy
nesting; and an admitted MPC child during drain. There was no existing test
dedicated to `VaultMeshFinancialQuorumAdapter` to adapt.

Accepted quorum tests mock `Bip340Verifier` to accept synthetic proofs. They
exercise admission and response handling, not cryptographic correctness. No
real network, vault, signer, financial database or durable admission store was
used. Compiler deprecation warnings and Mockito dynamic-agent warnings occurred.
This cached-classpath run is not the coordinator's pinned Gradle build,
PostgreSQL integration verification, Spring wiring test or release evidence.
No Gradle, Git writes, deployment, shared maintenance-file changes, financial
policy changes or removal of unknown coverage blockers were performed.

## Coordinator checkpoint and handoff — 2026-10-03

The coordinator reports 16 provider-suite cases passed in its focused run of
309 total cases, with an unrelated remote-client fixture failure. This is
separate reported evidence from the worker's earlier cached-classpath run;
the differing counts are not asserted to represent the same source snapshot.
The coordinator also independently added two actual PostgreSQL quorum tests.
Their execution result is not supplied in this checkpoint and is not inferred.

The bounded provider implementation, existing MPC fixture adaptation and new
maintenance suite are handed off with no further worker source/test edits.
Only this runbook was updated for the checkpoint. Coordinator owns the pending
full build, integration acceptance and PostgreSQL evidence. No complete-build
success, update readiness or removal of unknown coverage blockers is claimed.

## Final coordinator acceptance

Superseding the pending handoff above, the pinned Gradle `check bootJar` passed
with 1,776 total cases and zero failures/errors/skips, including all 20 current
provider maintenance cases and the five legacy MPC fixture cases. The two
independent quorum PostgreSQL cases passed within 14 full-schema cases; generic/
publisher PostgreSQL cases remain 14. Those tests mock the financial quorum port,
while using the actual migrated store and transaction manager. No live Vault,
independent release attestation, cryptographic proof qualification or complete
Cell update permission is inferred. All worker files are coordinator-owned.
