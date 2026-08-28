# Runbook — KFE dual-ledger / cold balance

Ops guide for incidents involving `available_sats`, `observed_sats`, cold Electrum
parity, and credit idempotency. Complements [BALANCE_CONTRACT.md](./BALANCE_CONTRACT.md).

## Quick map

| Symptom | Likely area | First check |
|---------|-------------|-------------|
| Cold shows more than Electrum | Live probe clobber / mempool filter | Logs `applyObserved`, quality |
| Cold shows less / stuck high after Electrum spend | Observe lag / ZMQ miss | Schedule cold-obs; force wait |
| Custodial can’t spend but chain has coins | available not credited | movements + min conf |
| Saldo “dobrou” | Dual credit path | unique index + dual_skip metrics |
| Locked forever | Outbound stuck | EXECUTING txs + outbox |

Kill switches (config / env):

- `kfe.cold-observation.enabled`
- `kfe.bitcoin.zmq.enabled`
- `kfe.custodial-deposit-observation.enabled`
- `kfe.balance-reconciliation.enabled`
- `kfe.onchain-balance-sync.enabled`

---

## Custodial drift

**Alert:** `KfeCustodialBalanceDrift` (`kfe_balance_drift_events_total`)

1. Confirm threshold: `kfe.balance-reconciliation.drift-threshold-sats`.
2. Query balances for `CUSTODIAL_ONCHAIN` wallets with large `|available - observed|`.
3. Check open inbound txs still `VALIDATING` (waiting confs) — **expected** drift.
4. If settled but available low: search `balance_movements` for missing
   `CREDIT_*` / double rows (should be impossible after V37 unique).
5. Resync path: restart is safe; do **not** manually credit without audit.

```sql
SELECT w.id, w.label, b.available_sats, b.observed_sats,
       (b.observed_sats - b.available_sats) AS drift
FROM financial.wallets_core w
JOIN financial.balances_core b ON b.wallet_id = w.id
WHERE w.kind = 'CUSTODIAL_ONCHAIN' AND w.status = 'ACTIVE'
ORDER BY abs(b.observed_sats - b.available_sats) DESC
LIMIT 20;
```

---

## Locked stuck

**Alert:** `KfeLockedFundsStuck`

1. List wallets with `locked_sats > 0` and txs in `EXECUTING` / `REQUIRES_RECONCILIATION` older than threshold.
2. Check execution outbox status / provider errors.
3. **Do not** zero `locked_sats` without matching settle/release movement.
4. Prefer re-drive outbox or mark failed via existing admin tooling + audit log.

---

## Dual credit

**Alert:** `KfeDualCreditSkipSpike` (skips are usually healthy)

1. Confirm unique index exists: `uq_balance_movements_tx_credit_type`.
2. For a suspect `transaction_id`:

```sql
SELECT movement_type, count(*), sum(amount_sats)
FROM financial.balance_movements
WHERE transaction_id = :tx
GROUP BY 1;
```

3. Expect at most one of each credit type. If user balance is still wrong, look for
   **manual** credits or non-listed movement types.

---

## Optimistic deferred

**Alert:** `KfeOptimisticDeferredSpike`

Common reasons (tag `reason`):

| reason | Meaning |
|--------|---------|
| `optimistic-zero-refused` | ZMQ tried to write 0 over positive |
| `optimistic-will-not-clobber-fresh-live` | Full collect won (good) |
| `optimistic-drop-too-large` | Implausible partial debit |

Action: ensure cold observe schedule still runs (`LIVE_MEMPOOL_AWARE` logs). Check Core ZMQ + scantxoutset busy.

---

## Probe deferred dominance

**Alert:** `KfeColdProbeDeferredDominance`

1. `kubectl logs deploy/kfe-service | grep 'applyObserved deferred'`
2. If scantxoutset busy: reduce concurrent cold wallets / wait.
3. If all `confirmed-utxo-set-will-not-clobber-live`: healthy after live path owns cold.

---

## Fee idempotent

**Alert:** `KfeFeeIdempotentSkipNoise`

1. Count `CREDIT_KEROSENE_FEE` per tx (should be 0 or 1).
2. Check SYSTEM_PROFIT wallet available growth rate vs expected fee volume.

---

## Smoke gates (local)

```bash
export KUBECONFIG=~/.kube/kind-config-kerosene-local
.local/smoke-venv/bin/python infra/scripts/beta/smoke-cold-e2e.py
.local/smoke-venv/bin/python infra/scripts/beta/smoke-money-e2e.py
```

Cold e2e proves: fund → observed up + inbound; forced external spend → observed down + outbound.

---

## Release checklist (staging → prod)

1. Migrations `V37` (credit unique) + `V38` (probe meta) applied.
2. Image digests for `kfe-service` (and `web-page` if FE shipped) recorded.
3. Config: `bitcoin.min-confirmations` ≥ 3; ZMQ endpoints reachable; descriptor-scan-range aligned.
4. Smokes green on staging.
5. Prometheus rules loaded (`kfe-balance-alerts.yml` + financial-alerts).
6. Kill switches documented for on-call.
7. Canary one pod; watch `kfe_cold_probe`, `kfe_balance_drift_events`, error logs 15–30m.
8. Rollback = previous digest + **no** reverse of V37/V38 (forward-only).

---

## Contact data (queries)

```sql
-- Last probe quality on cold wallets
SELECT w.id, w.label, b.observed_sats, b.observed_probe_quality,
       b.observed_probe_at, b.observed_probe_source
FROM financial.wallets_core w
JOIN financial.balances_core b ON b.wallet_id = w.id
WHERE w.kind = 'WATCH_ONLY' AND w.status = 'ACTIVE'
ORDER BY b.observed_probe_at DESC NULLS LAST
LIMIT 30;
```

## Frontend web deploy (cold signer gap)

Cold PSBT signer default lookahead is **100** (expand to **200** on miss) in
`cold_wallet_psbt_signer.dart`.

Local `flutter build web --release` currently fails on `dart:ffi` / `third_party/tor`
bindings (web cannot compile native FFI). Until that is fixed (conditional import /
stub for web):

- Ship signer changes via **mobile** build pipelines, or
- Fix web-conditional TOR stubs, then:

```bash
cd frontend && flutter build web --release
bash infra/docker/build-image.sh web-page
kind load docker-image kerosene/web-page:local --name kerosene-local
kubectl -n kerosene-local rollout restart deploy/web-page
```

## Balance smoke gate

```bash
bash infra/scripts/beta/run-balance-smokes.sh
```
