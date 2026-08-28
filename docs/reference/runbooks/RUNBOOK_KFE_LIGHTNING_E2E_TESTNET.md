# Runbook — KFE Lightning E2E (testnet)

End-to-end checklist for liquidação binária + Lightning in/out + channel ops on Bitcoin/Lightning **testnet** (or regtest).

## 0. Prerequisites

| Component | Required config |
|-----------|-----------------|
| Bitcoin Core | `BITCOIN_NETWORK=testnet` (or regtest), RPC enabled, wallet bootstrap |
| LND | REST + macaroon; `LIGHTNING_LND_REST_ENABLED=true` |
| KFE | profile with `kfe`, Flyway through **V44** |
| System wallets | `KfeSystemWalletService.ensureSystemWallets()` at bootstrap |
| Auth | Admin JWT for `/api/admin/kfe/**`; user JWT for `/kfe/**` |

```env
LIGHTNING_LND_REST_ENABLED=true
LIGHTNING_LND_BASE_URL=https://lnd:8080
LIGHTNING_LND_MACAROON=<hex>
BITCOIN_PRUNE_MB=0
KFE_SETTLEMENT_LIGHTNING_RISK_GATE_MODE=beta-pass   # use enforce after green path
KFE_CHANNEL_DRAIN_MONITOR_ENABLED=true
```

## 1. Health

```bash
curl -sS "$KFE/health/ready"
curl -sS -H "Authorization: Bearer $ADMIN_JWT" \
  "$KFE/api/admin/kfe/channels"
curl -sS -H "Authorization: Bearer $ADMIN_JWT" \
  "$KFE/api/admin/kfe/reserves/overview"
```

Expect: LND live when enabled; channels list (may be empty); reserves non-error.

## 2. Lightning receive (inbound)

1. User has **INTERNAL** spendable wallet.
2. Create payment request:

```bash
curl -sS -X POST -H "Authorization: Bearer $USER_JWT" \
  -H "Content-Type: application/json" \
  "$KFE/kfe/payment-requests" \
  -d '{"walletId":"'"$WALLET_ID"'","rail":"LIGHTNING","amountSats":10000,"memo":"e2e"}'
```

3. Confirm response includes `paymentRequest` (bolt11) + `paymentHash`.
4. Pay invoice from external testnet wallet/LND.
5. Wait for `KfePaymentRequestLightningMonitor` (default ~15s) or force via app logs.
6. Assert: PR `PAID`, wallet `available_sats` increased once (`CREDIT_PAYMENT_REQUEST`).

## 3. Lightning send (outbound)

1. Fund INTERNAL available ≥ amount + fee quote.
2. Quote + submit:

```bash
curl -sS -X POST -H "Authorization: Bearer $USER_JWT" \
  -H "Content-Type: application/json" \
  "$KFE/kfe/transactions/quote" \
  -d '{"rail":"LIGHTNING","direction":"OUTBOUND","amountSats":5000,"networkFeeSats":50}'

curl -sS -X POST -H "Authorization: Bearer $USER_JWT" \
  -H "Content-Type: application/json" \
  "$KFE/kfe/transactions" \
  -d '{
    "idempotencyKey":"e2e-ln-out-1",
    "rail":"LIGHTNING",
    "direction":"OUTBOUND",
    "sourceWalletId":"'"$WALLET_ID"'",
    "amountSats":5000,
    "networkFeeSats":50,
    "externalReference":"'"$BOLT11"'"
  }'
```

3. Assert path:
   - Settlement gate audit `KFE_SETTLEMENT_GATE` all flags 1 (or beta-limited documented).
   - Liquidity reservation `HELD` then `CONSUMED` on success.
   - Tx `SETTLED` only on LND `SUCCEEDED`; `REQUIRES_RECONCILIATION` on in-flight.

## 4. Channel lifecycle (admin)

```bash
# Dry-run open decision
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  "$KFE/api/admin/kfe/channels/open/evaluate" \
  -d '{"peerPubkey":"'"$PEER"'","localAmountSats":10000000,"estimatedFeeRateSatVb":5,"anchorsEnabled":true}'

# List rebalance queue after drain monitor
curl -sS -H "Authorization: Bearer $ADMIN_JWT" \
  "$KFE/api/admin/kfe/channels/rebalance/jobs?limit=20"

# Force rebalance worker batch (LND circular self-payment)
curl -sS -X POST -H "Authorization: Bearer $ADMIN_JWT" \
  "$KFE/api/admin/kfe/channels/rebalance/jobs/process?limit=3"
```

Open/close/ppm mutate LND only when decision **AND** gateway live.

Quick smoke script:

```bash
export KFE_BASE_URL USER_JWT ADMIN_JWT WALLET_ID
bash infra/scripts/beta/kfe-lightning-e2e-smoke.sh
```

## 5. Binary gate failure drills

| Drill | Expect |
|-------|--------|
| Double submit same idempotency | Same tx, no double debit |
| amount > available | `V_SALDO_DISP=0`, no reserve |
| LN capacity exhausted | `V_LIQUIDEZ=0` or enqueue fail |
| pending HTLCs ≥ max | `V_NO_JAMMING=0` |
| Concurrent 100 reserves (unit) | `KfeBalanceConcurrencyTest` green in CI |

## 6. Go-live promotion

1. Keep `beta-pass` until all drills green on testnet.
2. Apply keys from `src/main/resources/kfe-service-prod-hardening.properties`
   (or set `KFE_SETTLEMENT_LIGHTNING_RISK_GATE_MODE=enforce`).
3. Optional: deploy loopd and set `LIGHTNING_LOOP_ENABLED=true` + `LIGHTNING_LOOP_BASE_URL`.
4. Confirm node_exporter + `bitcoin-node-storage-alerts.yml` + `kfe-lightning-alerts.yml`.
5. Document peer denylist for known jammers.

## 7. Related code

- Settlement: `BinarySettlementGate`
- LN pay/invoice: `LndRestLightningClient`
- Liquidity: `KfeLightningLiquidityService` (V42)
- Inbound PR: `KfePaymentRequestLightningMonitor` (V41)
- Channels: `KfeChannelLifecycleService`, `KfeChannelDrainMonitor` (V43–V44)
- Plan: `docs/reference/runbooks/EXECUTION_PLAN_LIQUIDATION_LIGHTNING_ONCHAIN.md`
