# Channel producers and retention admission

Capacity/rebalance queue enqueue and state changes, capacity evaluation/scan,
drain monitoring, mesh commit retry/orphan release and statement retention are
guarded before their first mutation. Dependencies default unavailable until
the real maintenance guard is injected. Pure queue/status reads remain reads.

Local queue/retention completion is resolved only after observable transaction
success. Nested synchronous work shares its admitted root; provider/channel
completion retains the existing lifecycle uncertainty. Pending persisted jobs
remain named status blockers even if enqueue itself commits successfully.
Scanners may pause on admission failure; they do not discard pending jobs.

TTL orphan recovery additionally requires observed ACTIVE mode. An unrelated
admitted parent is not evidence that a legacy orphan belongs to that workflow.
Missing intent is preserved for manual recovery, never silently relabeled
RELEASED after a timeout. Failed retries/releases retain uncertainty.

`KfeProducerMaintenanceTest` covers queue/reconciler/retention root rejection,
storage outage, missing injection, nested local completion during drain and
orphan identity/TTL negatives. Existing capacity, rebalance and mesh suites also
run. Scheduled scans and every remote failure/recovery matrix are not certified
by these unit tests. No coverage blocker or complete-Cell execution gate is removed.
