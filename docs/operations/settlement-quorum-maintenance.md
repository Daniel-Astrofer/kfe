# Settlement and quorum admission

`BinarySettlementGate.evaluate`, `evaluateAndRequirePass`, both public audit
entrypoints and `KfeQuorumGateway.requireHealthyUnanimousConsensus` independently
admit through the durable maintenance guard. Missing injection defaults to
unavailable. Admission precedes flag evaluation, balance lock, provider probes,
consensus, audit and liquidity/metrics signals. No new transaction propagation,
threshold, flag ordering, risk-mode or proof-of-reserves policy is introduced.

The caller still owns the financial transaction and lock lifetime. The settlement
audit joins it; it does not survive rollback and must not be moved into a nested
REQUIRES_NEW transaction waiting on the same global audit appender lock.
Existing runtime failures converted into gate flags remain failures, not evidence
of completion. A consensus reply, passed gate, local commit or returned locked
balance is not proof of provider finality. All these boundaries use conservative
completion `false`; unresolved admissions require an audited reconciliation
contract that is not supplied by this change.

Synchronous nesting shares the already admitted workflow, allowing its work to
finish after drain starts. Fresh invocation during drain, maintenance storage
outage or missing injection refuses effects. No workflow token is exported and
later requests need new admission. An actual transaction without observable
synchronization refuses work before effects.

Bounded tests use the real guard over an explicit mock store and mock financial
ports. They exercise rejection before collaborators, success/commit/rollback/
unknown completion, caught transport rejection, synchronous nesting across drain
and unobservable transactions. Legacy gate tests retain original financial
assertions with explicit ACTIVE admission. These are not live quorum, remote
finality, Byzantine tolerance, complete host/embedded integration, source replay
or whole-Cell update qualification. All three unknown-coverage blockers remain.

Coordinator acceptance: the final pinned Gradle `check bootJar` ran all 47 cases
in this suite within 1,776 total cases, zero failures/errors/skips. Two additional
full-schema PostgreSQL quorum cases establish durable uncertainty after actual
commit/store recreation and rejection before the port on real drain. The port
is mocked; these are persistence/admission evidence, not live quorum finality.
