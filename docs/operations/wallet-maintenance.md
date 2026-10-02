# Wallet admission boundary

`KfeWalletService` and `KfeWalletNetworkService` require the injected real
maintenance guard. Construction without injection and unavailable admission
storage fail closed. Owner/capability and wallet-kind checks still precede the
first financial effect; the financial derivation and PSBT algorithms are unchanged.

Wallet creation spans the existing pending-wallet commit, quorum/key operations,
activation and post-activation hooks under one root. Update/archive, new receive
addresses, rotation, UTXO provider scans and cold PSBT creation are guarded.
UTXO lookup can run or abort `scantxoutset`, so it is not treated as a pure read.
Listing wallet labels/capabilities and reusing an existing address remain reads.

Remote quorum/provider return, a successfully created PSBT or local commit does
not prove completion of all callbacks. These roots conservatively retain
UNCERTAIN. Nested synchronous participants share a currently admitted parent and
may finish during drain; a later independent request must admit anew. No caller
may invent provenance from a wallet/transaction ID or use a permissive guard.

`KfeWalletMaintenanceTest` exercises rejection before effects, owner/kind checks,
pure reads, nested drain races, Spring transaction completion and remote failures
with synthetic dependencies and the real guard. Existing wallet suites also run.
This does not qualify remote wallet recovery or remove any coverage blocker.
Direct address/key services and startup wallet loading remain separate audit work.
