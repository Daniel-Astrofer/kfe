# Direct remote approval and notification admission

`KfeRemoteFinancialTransactionApprovalClient` and
`KfeRemoteFinancialNotificationClient` admit every typed outbound POST through
the mandatory, nonnull `@Autowired` maintenance setter. Their existing
constructors retain an unavailable default until injection. Missing injection,
drain rejection and admission storage failure propagate before transport.

Admission operation names are `remote-approval` or `remote-notification` followed
by the existing internal route path. They contain no credentials, user identifiers
or proof material. Unsupported legacy string approval methods still reject
locally. Missing-secret validation and request/header construction remain local
before admission; they perform no external effects.

The notification best-effort catch surrounds only admitted transport. HTTP
rejections and ordinary runtime transport failures retain their existing logging
and return behavior. Maintenance exceptions propagate, including any raised
inside transport; admission rejection is never treated as best-effort delivery.
Approval HTTP failures retain their structured authentication error mapping;
ordinary transport errors still propagate. Routes, headers, payloads and
financial policies are unchanged.

Every remote completion predicate is false. A successful HTTP response, a caught
delivery error or an observed local transaction commit cannot prove remote
completion. Nested calls reuse admitted parent provenance and leave that parent
uncertain even when it catches an approval failure or commits after a successful
remote call. A fresh direct call during drain is rejected. These clients do not
add asynchronous callbacks, continuations, retries or reconciliation authority.

## Existing outbound-conflicted contract defect

`notifyOutboundConflicted` supplies the pre-existing `confirmations = -1` to
`FinancialOutboundNotificationRequest`. That request constructor rejects negative
confirmations with `IllegalArgumentException("confirmations must be >= 0, got: -1")`
before `post` is invoked. This path is a pre-admission local validation failure:
it never calls the maintenance store or HTTP transport, including with ACTIVE,
DRAINING, unavailable injection or a storage outage.

Consequently, the outbound-conflicted notification currently cannot be delivered.
The shared request contract and the client's negative-confirmation policy are
preserved; resolving their mismatch requires a separately owned contract/policy
decision. This path is excluded from the typed effect parameterization and tested
separately for its exact exception and absence of admission and transport. It is
not evidence of successful remote delivery or complete notification coverage.

## Verification limits

The existing client tests explicitly inject `MaintenanceTestFixture.active()`.
`KfeRemoteEffectsMaintenanceTest` uses the real maintenance service over a mocked
store and mocked transport for all four typed approval and fourteen reachable
notification effect roots. It covers drain, unavailable injection and storage errors before transport;
admission order; ACTIVE success and error uncertainty; parent commit, rollback
and unknown outcome; nested admitted work during drain; mandatory
nonnull setters; maintenance rejection propagation; unobservable transactions;
and pure validation. The fifteenth notification method, outbound-conflicted,
has separate local-validation cases as described above.

The coordinator reported a focused run of 309 tests with nine failures, all for
outbound-conflicted; all other cases passed in that run. Those failures exposed
the pre-existing constructor rejection and incorrect effect expectations in the
test suite. The classification and tests were subsequently corrected without a
Gradle rerun. These revised cases have not been executed by this worker.

Gradle, full Spring wiring, actual HTTP providers and durable PostgreSQL behavior
are coordinator verification responsibilities. No unknown mutation, callback or
read-side-effect blocker is removed, and this bounded implementation supplies no
deployment or complete-Cell update permission.

Final coordinator acceptance supersedes the pending worker result: all 204
current remote maintenance cases passed in the full pinned Gradle `check bootJar`
run (1,776 total, zero failures/errors/skips). Existing client and notification
integration fixtures also passed with explicit admission. The conflict path's
four tests assert its local rejection; delivery remains broken as documented,
not repaired or counted as a successful remote effect.

## Consumer audit — diagnostic continuation

Read-only inspection of the isolated Core sources found no
`/internal/kfe/notifications/outbound-conflicted` controller mapping and no
`notifyOutboundConflicted` override in NotificationFinancialNotificationAdapter;
the shared default throws UnsupportedOperationException. The helper's conflict
notification also receives an unused refunded argument, so current payloads do
not distinguish refund from inconclusive reconciliation. Changing -1 to 0 would
repair construction but not supply that missing consumer or encode conflict.
Do not redirect this event to detected/confirmed methods: their warning/success
semantics differ. The existing V2 PaymentConflicted event requires a conflicting
txid that the inconclusive path cannot guarantee. A coordinated, authenticated
conflict-event receiver/adapter contract remains necessary; no code or financial
policy was changed by this read-only audit. Filesystem evidence is not a claim
about deployed images, remote provider behavior or source revisions not inspected.
