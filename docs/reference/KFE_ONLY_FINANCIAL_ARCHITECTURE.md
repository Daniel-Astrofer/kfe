# KFE-only Finance

KFE is sole money SoT. Package: **`com.kerosene.kfe`**.

Money, balances, statements, finance audit, payment requests, on-chain/Lightning exec, cold-wallet ops, treasury liquidity, reconcile, idempotency, finance auth → `com.kerosene.kfe`.

**Custody / signing:** vault mesh (`kerosene-vault`) holds FROST shares. KFE is the bank: emits **Intent**, consumes **Receipt**; Taproot PSBT cosign, day-advance + reshare, and governance rewards run on the mesh. See [`INFRASTRUCTURE.md`](INFRASTRUCTURE.md) and [`VAULT_MESH_PLAN.md`](../../VAULT_MESH_PLAN.md).

## Forbidden ownership

Broad backend must not own finance via:

- `source.ledger`
- `source.payments`
- `source.wallet`
- `source.bitcoinaccounts`
- finance under `source.transactions`
- treasury outside KFE + mesh (no HashiCorp Raft / mpc-sidecar as treasury SoT)
- package `source.kfe` (renamed to `com.kerosene.kfe`)

`kfe.legacy-financial.enabled` forbidden. No runtime switch back.

Deploy cutover: mesh on, mpc signing off (`kfe.vaultmesh.mesh-only`, `kfe.mpc.signing-enabled=false`). No gradual HashiCorp→mesh treasury dual-run.

## API surface

- `/kfe/dashboard`
- `/kfe/wallets`
- `/kfe/wallets/names`
- `/kfe/wallets/{walletId}/addresses/rotate`
- `/kfe/wallets/{walletId}/utxos`
- `/kfe/wallets/{walletId}/cold-wallet/psbt`
- `/kfe/transactions`
- `/kfe/transactions/quote`
- `/kfe/transactions/{transactionId}`
- `/kfe/users/{receiverIdentifier}/receiving-capabilities`
- `/api/admin/kfe/audit/*`

New finance endpoints only under `/kfe` or `/api/admin/kfe`.

Admin mesh health: `GET /api/admin/operations/vault-mesh` (probes mesh `/v1/health`).

## Gate

```bash
scripts/verify-kfe-only.sh
```

Fails if forbidden package/dep/route/legacy flag remains.

Strict docs:

```bash
STRICT_DOCS=1 scripts/verify-kfe-only.sh
```

## Migration

Legacy data: one-off migrate into KFE tables only. After that, KFE tables are SoT. Audit via KFE records, not revived legacy services. Treasury key material: greenfield mesh DKG — do not migrate Hashicorp-held wallet keys into Java.
