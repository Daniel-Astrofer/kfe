# KFE dual-ledger balance contract

Single source of product truth for what the app must show and what monitors may write.

## Wallet kinds

| Kind | Primary display (app) | Spend authority | Chain mirror |
|------|----------------------|-----------------|--------------|
| `WATCH_ONLY` (cold) | `observed_sats` only | None (PSBT external) | Absolute live UTXO + mempool |
| `CUSTODIAL_ONCHAIN` | `available_sats` | `available` after min confs | `observed_sats` best-effort |
| `INTERNAL` | `available_sats` | `available` | N/A |

FE: `frontend/lib/features/ledger/domain/balance_display.dart` (`BalanceDisplayRules`).

## Write policy for `observed_sats` (cold)

| Probe quality | May overwrite non-zero cold? |
|---------------|------------------------------|
| `LIVE_MEMPOOL_AWARE` (authoritative) | Always (wins over optimistic) |
| `OPTIMISTIC_DELTA` (ZMQ) | Yes if not zero wipe / not implausible drop **and** last LIVE is older than TTL (default 120s) |
| `CONFIRMED_UTXO_SET` (mempool-blind) | Only when previous was 0 (import seed) |
| `UNKNOWN` | Never |

Metadata columns: `observed_probe_quality`, `observed_probe_at`, `observed_probe_source`.  
Code: `KfeOnchainBalanceSyncService.decideWrite`. Config: `kfe.onchain-balance-sync.optimistic-live-ttl-seconds`.

## Credit policy for `available_sats`

- Exactly **one** user available credit movement per settled on-chain inbound transaction.
- Types (unique index): `CREDIT_INBOUND`, `CREDIT_PAYMENT_REQUEST`, `CREDIT_CUSTODIAL_DEPOSIT`, `CREDIT`.
- Fee: at most one `CREDIT_KEROSENE_FEE` per transaction.
- Pattern: **insert movement under unique constraint → then creditAvailable** (race-safe).
- Default min confirmations: `bitcoin.min-confirmations` (prod ≥ 3). Local may lower via env.

## Cold monitoring (internal + external)

| Source | History | Balance |
|--------|---------|---------|
| PSBT broadcast (app) | OUTBOUND `BITCOIN_CORE_COLD_PSBT` | Live observe / ZMQ |
| Electrum / external spend | OUTBOUND `BITCOIN_CORE_COLD_EXTERNAL_SPEND` | Outpoint + ZMQ optimistic (no full-funding guess) |
| External receive | INBOUND `BITCOIN_CORE_COLD_OBSERVER` | Live UTXO sum |
| ZMQ rawtx | Instant history + optional optimistic delta | Full observe debounced after |

Never SETTLED at 0 confirmations for cold external spends.

## Kill switches

| Property | Effect |
|----------|--------|
| `kfe.cold-observation.enabled` | Cold history + observe loop |
| `kfe.bitcoin.zmq.enabled` | Instant mempool path |
| `kfe.custodial-deposit-observation.enabled` | Electrum→custodial credits |
| `kfe.balance-reconciliation.enabled` | Drift logs/metrics only |

## Shared gaps

- `kfe.descriptor-scan-range` (default 200) — cold observe, onchain sync, listUtxos, ZMQ address expand.

## Explicit non-promises

- Sub-second Electrum parity always.
- Destination/fee on every external spend (needs Core rawtx).
- Lightning / external custody providers when marked not-live.
- Auto-unlock of stuck `locked_sats` without runbook.

## Ops

- Runbook: [RUNBOOK_KFE_BALANCE.md](./RUNBOOK_KFE_BALANCE.md)
- Prometheus rules: `infra/runtime/observability/prometheus/kfe-balance-alerts.yml`
- Smokes: `bash infra/scripts/beta/run-balance-smokes.sh`
- Migrations required: `V37` (credit unique), `V38` (probe meta)

## FE cold signer

- Default address lookahead: 100 (retry expand to 200 on no-match).
- File: `frontend/lib/features/financial_accounts/domain/services/cold_wallet_psbt_signer.dart`
- Unit tests: `cold_wallet_psbt_signer_test.dart` (pass).
- **Web deploy blocked** until `dart:ffi` / TOR stubs are web-conditional
  (`flutter build web` fails on native FFI). Mobile/app builds can still ship.
