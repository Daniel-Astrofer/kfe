# Address, key, statement and prepared/peer execution maintenance

The mandatory Spring guard defaults to unavailable on direct construction. Address
issuance admits before derivation-cursor allocation or Bitcoin RPC; MPC keygen
admits before its port call. System accounting-wallet initialization admits before
lookup/create/balance writes; configuration/capability and profit-wallet lookup
remain readable and never initialize wallets implicitly.

Statement roots admit before EntityManager flush, SQL/JPA upsert or event enqueue.
Best-effort catches execute inside the admitted boundary: a swallowed write error
cannot manufacture certain completion. Native upsert, REQUIRED propagation, FK
visibility, idempotency and display ordering remain unchanged.

Prepared payload persistence admits before lock/encryption/save; the owned payload
lookup remains read-only, with existing context, expiry, authenticated encryption
and hash checks. A claim token is not maintenance provenance. Peer inbound admits
before routing or financial mutations; inert/invalid input remains a no-op. No
signer, key generation, chain spend or custody recovery was performed by tests.

Address/key/statement/prepared/peer roots conservatively retain UNCERTAIN because
remote effects, nested publication and recovery completeness are not proved.
System-wallet local DB work observes transaction completion via the guard. An
already admitted parent may finish nested work during DRAINING; a fresh invocation
cannot borrow another workflow's outbox token or parent.

Payment-request monitor roots similarly admit before provider probes, row locks,
settlement and stream-index changes. Lightning SETTLED callbacks call the injected
transactional self proxy; indices advance only after it returns. A real Spring
proxy test checks the transaction/commit boundary and synthetic commit failure,
not a real ledger settlement. In-memory indices are not durable acknowledgements
or restart/replay evidence.

Static coverage blockers remain one. Cross-process/restart replay, remote completion
proof, embedded host security chains and complete mutation inventory are still
required; these guards alone do not make safeToUpdate true.
