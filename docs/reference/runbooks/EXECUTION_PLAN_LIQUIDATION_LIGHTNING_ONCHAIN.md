# Execution Plan: Liquidation / LN / On-chain

- Living doc. Update each PR.
- v1.0 · 2026-07-16
- Norm: KFE binary liquidation + liquidity + risk (Jul 2026); on-chain node storage addendum; code in `kfe-service` + adapters + infra
- Goal: ship each layer whole (no half-liquidation)
- `DONE` only with code + tests + min observability + §9 checklist

## Status legend
| Status | Meaning |
| --- | --- |
| `DONE` | shipped + tests + aligned |
| `PARTIAL` | useful base, gaps |
| `NOT_STARTED` | missing |
| `DOUBT` | product/infra undecided — do not code as settled |
| `BLOCKED` | waits prior item/decision |

On ship: update §3 matrix + §9 checklist + §4 doubts. No YAGNI flags/providers.

## Code rules
- Money: `long` sats (`BigDecimal` only for FX display)
- No `double`/`float` for balance/fee/liquidity/PoR
- One use case / one job; shared validation ports
- SOLID: submit orchestrates; rails via adapters/registry; ports in `rail/` + `common.financial`; LND/Flask/Core in adapters

```
controller/dto → application → domain/ledger → ports → adapters → infra
```

### Invariants
- Binary end state: `SETTLED` or `FAILED`/rollback
- `EXECUTING` / `REQUIRES_RECONCILIATION` = process only
- Atomic unit: whole sats
- Mutual distrust KFE↔LND/MPC/Core — positive proof before release
- PoR: `totalAssetsBtc ≥ totalOperationalExposureBtc` when active
- Crash: ACID rollback or outbox/idempotent resume
- Ledger via movements (`RESERVE`,`SETTLE_DEBIT`,`CREDIT_*`…); available credits unique by type/tx (V37); append-only audit

## Baseline (2026-07-16)

| Área | Onde | Notas |
| ------ | ------ | ------- |
| KFE-only financial SoR | `kfe-service`, docs `KFE_ONLY_*` | Legado proibido; gate `verify-kfe-only.sh` |
| Rails enum | `KfeRail`: INTERNAL, ONCHAIN, LIGHTNING | OK |
| Submit + idempotência DB | `KfeSubmitTransactionUseCase`, `KfeTransactionIdempotencyUseCase` | Reserva de chave antes do intent |
| Reserve/lock saldo | `KfeBalanceService.reserve` + movements | Lock de available → locked |
| Outbox execução | `KfeExecutionOutbox*`, workers | Retry em falhas retryable |
| Onchain outbound | `KfeOnchainOutboundExecutor` + PSBT gateway | Broadcast ≠ settle (correto) |
| Onchain inbound / conf | monitores, ZMQ, balance sync, payment request onchain | Relativamente maduro |
| Dual-ledger cold/custodial | `BALANCE_CONTRACT.md` | Contrato de `observed` vs `available` |
| Lightning **pay** LND REST | `LndRestLightningClient` | `/v2/router/send`; flag `lightning.lnd.rest.enabled` default **false** |
| Lightning outbound settle | `KfeLightningOutboundExecutor` | Chama payment gateway e settle |
| Invoice gateway (parcial) | BTCPay / Configurable | **Não** LND nativo no KFE |
| Adapter Python LND | `kerosene-rails/lightning_flask` | Status, invoice, pay, cohesion |
| Quorum/MPC boundary | `KfeQuorumGateway` + ports | Revalidação via portas; profundidade VLS a validar |
| Separação módulo/runtime | multi-module + k8s kfe-service | Advanced; not a liquidation blocker |

| Lacuna | Impacto |
| -------- | --------- |
| Receive Lightning desligado no produto | `KfeWalletNetworkService`: `boolean lightning = false` → sempre `KFE_LIGHTNING_RECEIVE_NOT… |
| LIGHTNING payment request does not emit bolt11 | Creates request without LND invoice |
| `LndRestLightningClient` **não** implementa `LightningInvoiceGateway` | Inbound depende de BTCPay/configurable |
| Binary flags (V_*) not explicit port + per-flag forensic log | Validation scattered; not doc AND-port |
| Sem `pg_advisory_xact_lock` explícito no submit | Concorrência via row lock/`FOR UPDATE` em balance — **validar equivalência** |
| Sem reserva de **liquidez Lightning** (V_LIQUIDEZ) até HTLC | Risco check-then-act de canal |
| Sem circuit breaker global de liquidez de saída | Pode aceitar saque sem liquidez de rede |
| Sem V_NO_JAMMING / limites HTLC / denylist peer | Gestão de risco LN ausente |
| Gestão de canais | `PARTIAL` (decision+LND+worker; Loop opcional) |
| Redis SETNX de idempotência | Só Postgres hoje; doc pede Redis + fallback DB |
| Débito definitivo vs reserve-until-proof | Outbound LN settle no success do pay; onchain espera conf — alinhar com “prova HTLC” |
| Política nó full-from-now (adendo) | Infra ainda `BITCOIN_PRUNE_MB` / `bitcoin-pruned-node` |
| Testes adversários 100 saques / jam / Redis down | Não sistematizados |

## Requirement matrix

### 3.1 Invariantes e liquidação binária (doc §1–§2)
| ID | Requisito | Status | Evidência no código | Próximo passo |
| ---- | ----------- | -------- | --------------------- | --------------- |
| INV-01 | Atomicidade 0/1 no resultado de negócio | `PARTIAL` | Status machine + outbox; estados intermediários processuais | Formalizar contrato: EXECUTING ≠ 0.5 sat; documentar + testes de crash |
| INV-02 | Apenas sats inteiros | `PARTIAL` | long sats no domínio; checar quotes/display | Grep gate + teste ArchUnit/proibido double em package financeiro |
| INV-03 | Desconfiança mútua KFE↔rail↔MPC | `PARTIAL` | Outbox + quorum port; LND confiado no pay result | Prova de invoice amount; revalidação MPC policy rigorosa |
| INV-04 | PoR: assets ≥ exposure | `PARTIAL` | Reserve overview admin; sem gate pré-exec obrigatório | Implementar `V_RESERVA_MAT` no submit path |
| INV-05 | Recuperação sem perda/duplicação | `PARTIAL` | Idempotência + outbox + ACID | Chaos tests; lock starvation timeouts |
| V-IDEMPOTENCIA | SETNX Redis + persistência + fallback DB | `PARTIAL` | Gate flag + DB unique | Redis opcional; DB SoT |
| V-LOCK-BANDO | Lock na mesma TX do débito | `PARTIAL` | `requireForUpdate` como V_LOCK_BANDO no gate | Advisory lock se testes exigirem |
| V-ATOMICIDADE | amount+fee inteiros | `DONE` | Gate + validator | — |
| V-SALDO-DISP | Saldo ≥ totalDebit sob lock | `DONE` | Gate sob FOR UPDATE + reserve | Testes 100 threads ainda abertos |
| V-DINHEIRO-REAL | Sem injeção fake em prod | `PARTIAL` | Gate: fail se prod+allow-simulated | Expandir checks de bootstrap |
| V-LIQUIDEZ | Liquidez saída + lock até HTLC | `PARTIAL` | Flag no gate; beta-pass / enforce | Implementar reservation real |
| V-P2P | Webhook assinado + poll | `PARTIAL` | Flag NOT_APPLICABLE no gate | Fase P2P |
| V-ASSINATURA-MPC | Sidecar revalida policy | `PARTIAL` | Gate chama quorum | Policy fields rigorosos |
| V-RESERVA-MAT | Simulação PoR pré-exec | `PARTIAL` | Flag; por default NOT_ENFORCED | PoR global |
| V-NO-JAMMING | HTLC limit + denylist | `PARTIAL` | Flag beta-pass/enforce | LND metrics |
| V-CIRCUIT-BREAKER | Limiar liquidez global | `PARTIAL` | Flag beta-pass/enforce | Metric + reject |
| PORTA-AND | Produto de flags; log cada flag | `PARTIAL` | `BinarySettlementGate` + `KFE_SETTLEMENT_GATE` | Completar liquidez real (Phase 5) |


### 3.2 Gestão de canais (doc §3)
| ID | Requisito | Status | Próximo passo |
| ---- | ----------- | -------- | --------------- |
| CH-OPEN | Abertura com flags capital/taxa/ancora/MPC/denylist | `PARTIAL` | Decision+LND open; admin API; **dead-man capacity controller** enqueue… |
| CH-REBAL | Rebal/swap com lucro matemático + profit wallet | `PARTIAL` | Decision+durable queue V44; Loop/submarine provider later |
| CH-CLOSE | Close cooperativo / anchors / freeze high fee | `PARTIAL` | Decision+LND close; capacity controller auto-close inactive |
| CH-PPM | Drain Deterrent / PPM dinâmico | `PARTIAL` | Decision+chanpolicy |
| CH-COST | Custos só da SYSTEM_PROFIT | `PARTIAL` | requireProfitWalletId on structural ops |
| CH-DEADMAN | Capacidade reativa a uso/receita sem admin | `PARTIAL` | `KfeChannelCapacityController` + V45 jobs; preferred-peers config requ… |


### 3.3 Resiliência a falhas (doc §4)
| ID | Cenário | Status | Notas |
| ---- | --------- | -------- | ------- |
| R-DEVICE | Clique + offline | `PARTIAL` | Idempotência + outbox; FE deve reconsultar estado |
| R-POD | Crash mid-validation | `PARTIAL` | ACID rollback; falta proof com advisory lock formal |
| R-LND | Nó down / DDoS | `PARTIAL` | Retry outbox; **VLS Nível 4** `DOUBT` (LND macaroon vs VLS real) |
| R-PEER | Force-close | `NOT_STARTED` | Anchors policy em infra LND |
| R-EXTERNAL | Timeout provider | `PARTIAL` | Outbox retries; P2P poll depois |


### 3.4 Superfícies de ataque (doc §5–§6)
| Prioridade | Ameaça | Status mitigação |
| ------------ | -------- | ------------------ |
| CRÍTICA | Race saldo/liquidez | `PARTIAL` (lock+reservation; 100-thread unit) |
| CRÍTICA | Bypass MPC | `PARTIAL` |
| ALTA | Jamming/griefing | `PARTIAL` |
| ALTA | Replay idempotência | `PARTIAL` (DB bom; Redis opcional) |
| MÉDIA | Spoof webhook P2P | `PARTIAL` (desenho) |
| MÉDIA | Force-close griefing | `NOT_STARTED` (anchors) |


### 3.5 Checklist obrigatório do doc §7 (espelho operacional)
| # | Item | Status |
| --- | ------ | -------- |
| 1 | Fluxo liquidação em uma TX + lock | `PARTIAL` |
| 2 | Reserva liquidez até HTLC | `DONE` |
| 3 | MPC revalida independente | `PARTIAL` |
| 4 | Limites HTLC + denylist | `PARTIAL` (limits/denylist; stuck monitor residual) |
| 5 | Circuit breaker liquidez | `DONE` |
| 6 | Anchors 100% canais + RBF controlado | `NOT_STARTED` / `DOUBT` (config LND) |
| 7 | Log imutável por flag 0/1 | `DONE` |
| 8 | Reserva on-chain mín. + reverse-swap | `NOT_STARTED` |
| 9 | Testes adversários | `PARTIAL` (unit concurrency) |
| 10 | Replicação síncrona estado LN | `DOUBT` (infra LND/VLS) |


### 3.6 Adendo nó on-chain (2026-07-16)
| ID | Requisito | Status | Evidência |
| ---- | ----------- | -------- | ----------- |
| NODE-01 | Parar prune de blocos **novos** | `DONE` | `BITCOIN_PRUNE_MB=0` default → `prune=0` |
| NODE-02 | Não ressincronizar histórico já podado | `DONE` | Sem reindex forçado de histórico |
| NODE-03 | Nome/serviço `bitcoin-pruned-node` | `PARTIAL` | Nome legado; renomear com cuidado |
| NODE-04 | Capacidade de disco + alertas | `PARTIAL` | `bitcoin-node-storage-alerts.yml` (node_exporter) |


### 3.7 Lightning produto (fora do PDF mas needed for “implementar Lightning”)
| ID | Requisito | Status |
| ---- | ----------- | -------- |
| LN-IN-01 | Emitir invoice (bolt11) no receive/payment request | `PARTIAL` (LND invoice + payment request; testnet E2E open) |
| LN-IN-02 | Poll/settle inbound com prova amount+hash | `PARTIAL` (monitor PR Lightning + network monitor outbox) |
| LN-OUT-01 | Pagar invoice com fee limit + timeout | `PARTIAL` (status matrix + fail-closed) |
| LN-OUT-02 | Settle only on terminal success; fail unlock | `PARTIAL` (SUCCEEDED only; in-flight → markUnknown) |
| LN-CAP-01 | `canReceiveLightning=true` when live | `DONE` (invoice gateway isLive + INTERNAL wallet) |
| LN-PROV-01 | Provider preferencial LND invoice+pay | `DONE` (auto: lnd first) |
| LN-ADAPTER-01 | KFE→LND direto | `DONE` (D1=A) |


### 3.8 Core on-chain a melhorar
| ID | Tema | Status | Foco |
| ---- | ------ | -------- | ------ |
| OC-01 | Outbound PSBT + conf monitor | `PARTIAL` | Ambiguous execution, fee, stuck |
| OC-02 | Inbound custodial credits | `PARTIAL` | min confs, idempotência crédito |
| OC-03 | Cold observe + ZMQ policy | `PARTIAL` | já documentado em BALANCE_CONTRACT |
| OC-04 | Payment request onchain monitor | `PARTIAL` |  |
| OC-05 | System funds/profit wallets | `PARTIAL` |  |
| OC-06 | Full-blocks-from-now storage | `DONE` (entrypoint) | adendo |


## Open doubts

- 

## Phase order

- Phase 0  Foundations & contratos
- Phase 1  Binary settlement gate (flags + audit)
- Phase 2  On-chain core harden (sem LN ainda se necessário)
- Phase 3  Lightning outbound production-safe
- Phase 4  Lightning inbound (invoice + settle + capabilities)
- Phase 5  Liquidez, circuit breaker, jamming defenses
- Phase 6  Channel lifecycle (open/rebal/close/PPM) — YAGNI até 3–5 estáveis
- Phase 7  Adendo full-blocks-from-now + ops
- Phase 8  Adversarial tests + observabilidade + go-live gates
- Phase 7 (storage) pode rodar em paralelo com 3–5 (infra).
- Phase 2 pode paralelizar com 1 se não tocar no mesmo gate.
- 

## Phase detail

### Phase 0 — Foundations e contratos de domínio
- [ ] README curto em `docs/reference/runbooks/` linkando este plano + BALANCE_CONTRACT
- *Status fase:** `PARTIAL` (decisões fechadas; gate de double e README ainda abertos)

### Phase 1 — Porta lógica binária de liquidação
- *Flags stub explícitos (retornam 1 com `reason=NOT_APPLICABLE` até Phase 5):**
- `application/settlement/BinarySettlementGate.java`
- `application/settlement/SettlementFlag.java` + `FlagEvaluation`
- `KfeAuditLogService` evento `KFE_SETTLEMENT_GATE` com mapa de flags
- Integrar em `KfeSubmitTransactionUseCase` sem inchá-lo (S do SOLID)
- [x] Toda submit gera audit com cada flag 0/1 (`KFE_SETTLEMENT_GATE`, REQUIRES_NEW)
- *Status fase:** `PARTIAL` (código + unit tests; concorrência 100 threads e liquidez real pendentes)
- `com.kerosene.kfe.application.settlement.BinarySettlementGate`
- `SettlementFlag`, `FlagEvaluation`, `SettlementGateCommand`, `SettlementGateResult`, `SettlementGateRejectedException`
- Integração em `KfeSubmitTransactionUseCase.validateQuoteAndQuorum`
- Audit event `KFE_SETTLEMENT_GATE`
- Props: `kfe.settlement.lightning.risk-gate-mode`, `por-gate-enabled`, `allow-simulated-balances`

### Phase 2 — Endurecer core on-chain
- [ ] Smokes balance (`run-balance-smokes.sh`) verdes
- *Status fase:** `PARTIAL` (base existe)

### Phase 3 — Lightning outbound production-safe
- *Status fase:** `PARTIAL`

### Phase 4 — Lightning inbound (produto completo)
- [ ] Capabilities sem `KFE_LIGHTNING_RECEIVE_NOT_CONFIGURED` em env live
- *Status fase:** `NOT_STARTED` (monitor parcial)

### Phase 5 — Liquidez, circuit breaker, anti-jamming
- *Status fase:** `NOT_STARTED`

### Phase 6 — Gestão de canais (doc §3)
- *Trabalho:** domínio `ChannelPolicy`, jobs, integração LND, funding PSBT onchain via **vault mesh** (não mpc-sidecar), auditoria. CHANNELS→LND inject = **fail-closed stub** (`ChannelsMeshInjectGateway` / `CHANNELS_MESH_INJECT_NOT_WIRED`); full inject still planned — do not invent channel capital from LND wallet alone.
- *Status fase:** `PARTIAL` (decisions + LND open/close/ppm + admin API; rebalance execution queued)
- `application/channel/KfeChannelDecisionService`
- `service/KfeChannelLifecycleService`
- `rail/LightningChannelGateway` + LND impl
- `controller/KfeChannelAdminController`
- migration `V43__channel_operation_decisions.sql`

### Phase 7 — Adendo storage Bitcoin (full blocks from now)
- *Status fase:** `NOT_STARTED`

### Phase 8 — Testes adversários, observabilidade, go-live
- *Status fase:** `NOT_STARTED`

## Target packages

- | Application settlement | `com.kerosene.kfe.application.settlement` | Gate, flags, command/result |
- | Domain liquidity | `com.kerosene.kfe.model` + repo | LiquidityReservation entity |
- | Rail LN | `com.kerosene.kfe.rail` | Invoice no LND client; status normalizer |
- | Service LN | `com.kerosene.kfe.service` | PaymentRequest LN, capabilities, liquidity service |
- | Custody / signing | vault mesh (`kerosene-vault`) | Intent/Receipt, Taproot PSBT; mpc-sidecar off no go-live |
- | Adapter Python | `kerosene-rails/lightning_flask` | Se D1=B, cliente Java HTTP |
- | Infra BTC | `infra/runtime/bitcoin` | prune policy |
- | Docs | `docs/reference/runbooks/` | este plano, runbooks |
- | Tests | `src/test` | unit + concurrency + fake LND |
- *Não criar** segundo ledger fora de `com.kerosene.kfe`.
- *Não** reintroduzir `source.ledger` / legacy.
- --

## Global DoD

- -

## Progress checklist

### Foundations

- [x] Phase 0 parcial (decisões) — 2026-07-16
- [x] Decisões D1 D2 D4 D5 D6 D7 — 2026-07-16
### Liquidação

- [x] BinarySettlementGate + audit flags — 2026-07-16
- [x] Concurrency tests saldo — 2026-07-16
- [ ] V_RESERVA_MAT real ou waiver — ____-__-__
### On-chain

- [x] Outbound ambiguous path (PSBT) already present — 2026-07-16 verified
- [ ] Inbound credit unique all paths — ____-__-__
- [ ] Balance smokes green — ____-__-__
### Lightning

- [x] Outbound status matrix — 2026-07-16
- [x] Invoice gateway LND — 2026-07-16
- [x] Payment request bolt11 — 2026-07-16
- [x] Capabilities LN live — 2026-07-16
- [ ] Inbound settle 1x end-to-end testnet — ____-__-__
### Liquidez / risco LN

- [x] Liquidity lock até HTLC (reservation row V42) — 2026-07-16
- [x] Capacity probe + circuit breaker floor — 2026-07-16
- [x] HTLC limits + denylist (`KfeLightningJammingGuard`) — 2026-07-16
### Canais

- [x] Open/rebal/close/PPM binary decisions + admin API — 2026-07-16
- [x] Durable rebalance queue + profit capacity check — 2026-07-16
- [x] Automated drain detection job (PPM + enqueue) — 2026-07-16
- [x] Circular rebalance worker (LND self-payment) — 2026-07-16
- [x] Loop/submarine fallback (`LightningLoopClient`) — 2026-07-16
### Infra

- [x] Full blocks from now (BITCOIN_PRUNE_MB=0 default) — 2026-07-16
- [x] Disk alerts — 2026-07-16
### Go-live

- [x] Checklist doc §7 major items code-complete — 2026-07-16
- [x] Adversarial suite (unit concurrency saldo+liquidez) — 2026-07-16
- [x] Testnet E2E runbook documented — 2026-07-16
- [ ] Testnet E2E executed on live LND — ____-__-__
---
## 10. Ordem de PRs sugerida (pequenos, revisáveis)

---
## 11. Anti-padrões (rejeitar em review)

---
## 12. Referências rápidas de código

---
## 13. Log de progresso (append-only)

---
## 14. Próxima ação imediata (para não perder contexto)

### Admin channels API

- `GET /api/admin/kfe/channels`
- `POST .../open|/open/evaluate`
- `POST .../rebalance|/rebalance/evaluate`
- `POST .../close|/close/evaluate`
- `POST .../ppm|/ppm/evaluate`
### Node storage

---
