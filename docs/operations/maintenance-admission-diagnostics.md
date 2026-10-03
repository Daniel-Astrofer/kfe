# Inspecting unresolved maintenance admissions

Authenticated operators with positive numeric identity and ROLE_ADMIN may use
`GET /api/admin/kfe/maintenance/admissions?limit=50`. HEAD uses the same query
without a response body. This exact read control remains accessible during
DRAINING; it never invokes admission, resolution, cancellation, replay or resume.
Other verbs, suffixes, encoded aliases and ambiguous paths are not exempted.

Response schema: `kerosene.kfe-maintenance-admissions/v1`, with observedAt, mode,
changeId, revision, diagnosticOnly=true, entries and nextCursor. Each entry
contains admission ID, bounded operation label, admittedRevision, state,
admittedAt and optional parentAdmissionId. No financial request/proof, credentials,
operator-supplied SQL or continuation capability is included. An admission ID is
not authorization to complete work. Parent metadata does not recreate a closure.

The default page size is 50, minimum 1, maximum 100. Rows are unresolved only:
WAITING, READY, IN_FLIGHT, UNCERTAIN, and defensive unknown states. COMPLETED and
CANCELLED are excluded. Ordering is admittedAt ascending, then UUID ascending;
the cursor contains the last returned timestamp/UUID, not an offset. Return
nextCursor as the next request's cursor, unchanged. It is a canonical, versioned,
bounded base64url position marker, not a signed proof or bearer credential; a
valid operator may choose a position. Do not treat an empty last page as proof
that every outstanding admission was examined.

Each page uses an independent read-only REPEATABLE_READ transaction. Mode,
revision and entries share that page's PostgreSQL MVCC snapshot. Pages from
different requests are not one long-lived snapshot: entries may resolve between
them and later pages can have a different revision. CURRENT_TIMESTAMP identifies
the database transaction time, not a remote acknowledgement or deadline.
No write lock on maintenance control is acquired; drain/admission keep their
existing serialized path. Queries have a five-second transaction timeout and
bounded output. Large-backlog sort/index performance is not load-qualified;
existing V57/V58 schema is retained, with no new migration or table cleanup.

Responses and fixed 400/503 query errors carry Cache-Control:no-store. Invalid
limits/cursors fail locally before database access; database/transaction errors
return unavailable rather than fabricated ACTIVE or empty success. Authentication
and existing method/URL authorization remain mandatory. Full source/embedded
host dispatch and real operator UI are not qualified by MockMvc alone.

## Why this does not resolve uncertainty

Current admissions do not durably bind an exact financial operation, external
provider result, authenticated completion proof and recovery policy. The operation
label and parent ID cannot establish all remote effects completed or rolled back.
These diagnostics intentionally provide no clear-by-ID, expiry, acknowledgement
override, retry runner or safeToUpdate field. Inspect status separately; all
unknown-coverage blockers remain nonzero. An audited proof/reconciliation contract
is still required before implementing terminal resolution or a safe updater.

## Accepted verification and recovery limits

The final pinned Gradle `check bootJar` passed: 1,828 tests, no failures/errors/
skips. This includes 25 query cases, 14 controller cases (including actual barrier
plus MockMvc), ten additional perimeter cases and three new actual full-schema
PostgreSQL cases. PostgreSQL proves tied-timestamp keyset ordering/provenance,
unchanged metadata during real drain and one MVCC snapshot despite an independently
committed resolution between control and row reads. Financial provider finality,
embedded routing, large-backlog load, real operator UI and jctl are not inferred.

No schema migration or persisted state rewrite is introduced. Removing this
endpoint by reverting its binary code must leave admission rows and maintenance
control unchanged; an older client/image may return 404, never clearance. After
a browser/client interruption, authenticate again, reread maintenance status and
start a fresh diagnostic page if needed. Cursors are positions, not durable
exports, transaction proofs or acknowledgement/replay tokens. Do not delete rows
or resume merely because the diagnostic endpoint is unavailable or pages are empty.
