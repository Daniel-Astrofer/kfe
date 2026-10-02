# Notification admission boundary

The outbox service, worker and processor require real durable admission before
claim, status writes or delivery. Missing injection/storage fails closed. One
synchronous worker root covers its claim and the entire processing batch; nested
work can finish after drain starts, but a new worker tick cannot claim.

The original statuses, five-minute claim lease, backoff, retry limit and dead
letter behavior are retained. Lease expiry or a populated transaction ID grants
no maintenance provenance. A successful claim with missing reload remains
UNCERTAIN, not an apparently completed empty batch. A genuinely empty tick or
lost claim race can complete locally after successful transaction completion.

The remote notification port is best effort and can swallow provider failures.
Therefore DELIVERED, normal port return and a committed local status update do
not certify recipient delivery. Nonempty batches and direct delivery/status
roots stay UNCERTAIN, including caught failures. No expiry/force-clear is added.

`KfeNotificationMaintenanceTest` covers independent roots, missing injection,
storage failure, drain during a batch, transaction rollback/unknown completion,
claims/retries/dead letters and the actual best-effort remote client with a mocked
transport. Webhook children are separately covered by V58 and their own tests.
Recipient acknowledgement/reconciliation and other event publishers remain work;
these tests do not qualify a complete-Cell update or clear coverage blockers.
