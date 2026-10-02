# Network settlement maintenance

`KfeInboundSettlementService`, `KfeNetworkMonitor` and
`KfeOutboundConfirmationMonitor` require Spring injection of the real
`KfeMaintenanceGuard`. Their existing constructors default to unavailable
admission; there is no implicit ACTIVE fallback. A missing guard, store outage
or DRAINING rejection prevents new financial inspection and settlement work.

| Entry | Admission operation | Boundary |
| --- | --- | --- |
| Inbound `settle(proof)` | `network.inbound-settle` | Before locked outbox/transaction reads and every possible first write |
| Inbound reconciliation candidate | `network.inbound-inspect` | Before transaction inspection, onchain proof lookup or Lightning provider calls |
| Open/settled outbound confirmation candidate | `network.outbound-confirmations` | Before chain probe, managed-entity changes, confirmation helper or disappeared-transaction handling |
| Inbound confirmation candidate, including cold observations | `network.inbound-confirmations` | Before chain probe, confirmation helper or cold observation changes |

Candidate-list reads and RPC bean resolution may precede admission. Each
candidate has its own root; a scheduler pass is not a blanket admission for
the whole batch. The first maintenance rejection ends that pass, preserving
the current and subsequent candidates. No financial failure, retry, claim
clearance, confirmation change, probe timestamp or reserve release is created
by rejection. The existing schedule may retry admission on its next pass.
Ordinary financial/provider failures retain the existing handling.

Transaction IDs, outbox IDs/claims, provider references, txids, payment hashes
and idempotency keys are financial correlation data. They do not prove durable
maintenance provenance and cannot reopen an independent root during drain.
An already admitted synchronous workflow using the same real guard can finish
its nested inspection/settlement during drain without another admission. This
also covers drain beginning between a monitor's provider lookup and its nested
settlement call. A later scheduler candidate still requires fresh admission.

All four operations use an unconditional false completion predicate. Financial
success, SETTLED/DISPATCHED state, a probe response or helper return does not
prove remote or callback completion. Nested uncertainty propagates to the
admitted parent. The guard observes transaction completion where available;
even commit does not make these roots certain. Rollback, unknown completion,
provider exceptions and swallowed notification/sync/helper failures cannot
manufacture completed admission. Failed persistence of resolution retains the
durable blocker under the existing guard policy.

The financial algorithms are unchanged: inbound proof/amount validation,
provider-reference and ledger idempotency, watch-only/custodial balance paths,
fees, statements, notifications, outbox dispatch and claim clearing; outbound
confirmation priority and progress before settlement; confirmation decreases,
conflicts/reorg handling, cold observations and disappeared-transaction
replacement/input/reconciliation policy all retain their existing behavior.

## Evidence and remaining gaps

The three existing service suites explicitly install ACTIVE mock-store fixtures
through `KfeMaintenanceService`; their financial expectations are unchanged.
`KfeNetworkSettlementMaintenanceTest` uses the real guard with mocked storage,
providers and financial collaborators. It covers DRAINING, outage and unavailable
injection across direct settlement, onchain/Lightning inspection, open/settled
outbound confirmation and inbound/cold confirmation paths; mandatory injection;
no effects on rejection; scheduler pause; separate candidate admission;
synchronous drain completion; uncertain resolution and Spring completion callbacks.

This is bounded root admission coverage, not complete Cell update readiness.
The static `mutationCoverageUnknown`, `callbackCoverageUnknown` and
`readSideEffectsUnknown` blockers remain unchanged. Durable continuation capture
and asynchronous/cross-thread provenance belong to their owning integrations;
financial identifiers and an after-commit publisher call do not establish that
coverage here. Independent admission records remain uncertain pending evidence
from the shared maintenance integration. No expiry, resume or operational override
is added to clear them.

Standalone Java verification compiled the three changed production classes and
four focused suites against cached dependencies, with compiler output isolated
in a temporary directory, and ran 69 tests successfully through the JUnit launcher.
The real guard/store interfaces were compiled from source for that check; other
unchanged collaborators used existing compiled classes. This is not a full build
or database/runtime integration check.

No Gradle, Git, production RPC, keys or signers are used for this work. Coordinator
verification must run the four focused suites under Gradle and the required
integration checks before claiming build, database admission serialization or
runtime readiness.
