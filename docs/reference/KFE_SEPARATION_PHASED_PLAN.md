# KFE Separation Plan

> **Historical / deprecated plan.** This file records the phased extraction
> work and may contain completed or superseded steps. It is not the current
> operational status. Use [`../en/STATUS.md`](../en/STATUS.md) or
> [`../pt-BR/STATUS.md`](../pt-BR/STATUS.md).

Split finance (KFE) from Core (identity/auth/notify/ops). KFE = money SoT. Treasury signing = **vault mesh** (`kerosene-vault`), not mpc-sidecar / HashiCorp Raft.

## Now
- Domain **`com.kerosene.kfe`** (renamed from historical `source.kfe`); routes `/kfe/**`, `/api/public/kfe/**`, `/api/admin/kfe/**`
- Schema `financial` (V12+); V23 drops legacy tables in dev/test
- Still one Gradle/Spring process per shard in some overlays; local-full runs separate `kfe-service` workload
- No `com.kerosene.kfe.*` imports outside KFE; KFE does not import `source.auth.*` (ports)
- Custody: `kfe.vaultmesh.*` Intent/Receipt; local-full/go-live = mesh on, `kfe.mpc.signing-enabled=false`
- Gate: `scripts/verify-kfe-only.sh`

## Target
| Context | Owns |
| --- | --- |
| Core | identity, auth factors, notify, app shell, non-money sovereign |
| KFE | wallets, balances, txs, rails, PSBT orchestration, reserves, tax, finance audit/outbox; emits Intent to mesh |
| Vault mesh | FROST shares, DKG/reshare, Taproot cosign, day-advance, governance rewards |

Core ↔ KFE via HTTP/event/ports only. KFE ↔ mesh via vaultmesh adapter (token lab / mTLS go-live).

## Phase 0 — Lock KFE-only
- goal: garantir que o monólito atual esteja limpo antes de separar fisicamente.
- Manter o flag legado `kfe.legacy-financial.enabled` ausente de código executável e testes.
- Fazer `scripts/verify-kfe-only.sh` passar sem exceções.
- Manter os pacotes legados ausentes: `source.ledger`, `source.payments`, `source.wallet`, `source.bitcoinaccounts`.

## Phase 1 — Cut Core↔KFE imports
- goal: same deploy; invert deps to contracts.
- `FinancialWalletProvisioningPort` wallet provision on signup. Done.
- `NotificationAuditPort` durable notification audit. Done.
- `FinancialRailHealthPort` finance rail health. Done.
- `FinancialRailProductionSafetyPort` prod rail safety checks. Done.
- `FinancialAuditIntegrityPort` audit root for sovereign UI. Done; remote Core→KFE client.
- `FinancialOperationsAdminPort` admin blockchain/LN/logs/metrics. Done.
- `FinancialAuthorizationPort` for transactional auth.
- `FinancialReservePort` for reserve overview.
- Replace outside KFE domain imports with ports/interfaces. Done for `main`/`test` (historical notes used `source.kfe.*`; current package is `com.kerosene.kfe`).
- ArchUnit: fora de KFE, nenhum pacote depende de `com.kerosene.kfe..` (e equivalentes legados bloqueados).
- Harden `scripts/verify-kfe-only.sh` to block outside KFE domain imports. Done.
- `source.auth`/`common`/`notification`/`security` do not import KFE internals.
- Regressões de dependência são bloqueadas por ArchUnit e pelo script `scripts/verify-kfe-only.sh`.

## Phase 2 — Split repo modules
- goal: transformar separação lógica em separação compilável.
- Gradle polyrepo. Auth, KFE, Shared and Contracts now have independent repositories connected by composite builds for local development.
- `kerosene-core`
- `kerosene-kfe` — independent repository holding `com.kerosene.kfe`
- sibling `kerosene-contracts` — substituted for `io.kerosene.contracts:kerosene-contracts`
- Mesh client lives in kfe vaultmesh adapter (not a revived mpc-sidecar client as primary)
- Move KFE domain into `kfe-service`. Done under
  `src/main/java/com/kerosene/kfe`.
- Move shared DTOs/contracts to `kerosene-contracts` (no JPA entities). Done for finance ports…
- Manter removidos os imports `source.auth.*` / `source.notification.*` / `source.security.*` dentro de KFE. Done + ArchUnit.
- Extract remaining `source.sovereign.quorum` behind `FinancialQuorumPort`. Done…
- Separar testes por serviço e manter um pacote de testes de contrato. O KFE e Contracts possuem builds independentes.
- O repositório `kerosene-contracts` compila isolado, sem Spring/JPA/implementações. Done.
- `kerosene-shared` builds alone; no impl-package deps. Done.
- `kerosene-kfe` builds/tests alone and holds `com.kerosene.kfe`. Done.
- Core builds using contracts/shared/KFE as external deps. Done for first physical cut.

## Phase 3 — Split runtime/infra
- goal: rodar KFE como serviço próprio, ainda no mesmo cluster local/prod.
- Criar imagem/container `kfe-service` separado de `auth-service-*`. Caminho canônico: `infra/docker/images/kfe-service/Dockerfile`.
- Adicionar `kfe-service` ao compose/k8s. Iniciado com manifests Kubernetes…
- Variáveis próprias: banco, Redis/outbox, Bitcoin Core, LND, **vault mesh** (`kfe.vaultmesh.*`), políticas de release — **não** mpc-sidecar no path local-full/deploy.
- Colocar Core → KFE atrás de cliente HTTP interno ou mensageria confiável. Iniciado…
- Split health: Core `/health` must not hide finance failure; KFE exposes own finance health; treasury via mesh `/v1/health`.
- `kfe-service` has own image/workload via Kustomize. Done for local/staging/production overlays…
- Local Compose overlay `kfe-split` / local-full mesh bridge. Done for local sim.
- Core sobe sem inicializar beans KFE quando perfil split. Preparado…
- KFE sobe e processa financeiro independentemente. Preparado…

## Phase 4 — Split data/ops ownership
- goal: impedir que o Core toque dados financeiros mesmo por acidente.

## Phase 5 — FE/admin explicit boundary
- goal: a UI entende que financeiro é um serviço separado.
- `CoreApiClient` for auth/notificações/perfil.
- `KfeApiClient` for carteiras/transações/payment requests/reservas/auditoria financeira.

## Phase 6 — Final gates / prod cut
- goal: permitir corte seguro sem regressão financeira.
- `scripts/verify-kfe-only.sh`
- Mesh-only go-live profile: `kfe-service-vaultmesh-go-live.properties` (mesh on, mpc signing off). Clean cut — no dual treasury signing.

## Suggested order
- Corrigir o gate `verify-kfe-only.sh`. **Done nesta entrega.**
- Introduzir portas e remover imports KFE fora do KFE. **Done logicamente no monólito nesta entrega.**
- Add ArchUnit guardrails. **Done…**
- Separar Gradle em repositórios compiláveis. **Contracts, Shared e KFE foram extraídos e ligados por composite builds locais.**
- Separar containers/deploy + vault mesh cutover. **Iniciado / local-full mesh-only.**
- Separar permissões de banco.
- Separar frontend/admin clients.
- Fazer cutover de runtime Core/KFE com testes de contrato e degradação (independente do cutover treasury mesh, já mesh-only no deploy path).

## Done when
- O Core compila sem importar `com.kerosene.kfe..` (histórico: `source.kfe..`). **Validado e formalizado em ArchUnit/script.**
- O KFE compila sem importar `source.auth..`, `source.notification..`, `source.security..` ou `source.sovereign..`.
- `kerosene-contracts` builds alone; no imports of KFE/auth/notification impl packages.
- O repositório `kerosene-shared` compila isolado e não importa pacotes de implementação.
- O repositório `kerosene-kfe` compila/testa isolado e o Auth compila consumindo seu artefato transitório.
- O workload Kubernetes `kfe-service` renderiza nos overlays local/staging/production.
- Local-full/deploy: vault mesh on, mpc-sidecar absent, Raft treasury off.
- O boundary de runtime Core/KFE é protegido por teste arquitetural.
- Os primeiros clientes remotos Core → KFE estão atrás de ports.
- O KFE é iniciado, testado e versionado como serviço próprio.
- Somente o KFE tem permissão de escrita no schema financeiro.
- Todas as rotas financeiras ativas passam por `/kfe/**`, `/api/public/kfe/**` ou `/api/admin/kfe/**`.
- Não existe flag de retorno ao financeiro legado.
- O app continua autenticando e abrindo o shell mesmo com KFE indisponível.
- Operações financeiras falham de forma segura, auditável e idempotente quando KFE, mesh ou rails externos estão indisponíveis.
