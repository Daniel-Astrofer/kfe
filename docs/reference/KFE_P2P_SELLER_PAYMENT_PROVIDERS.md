# KFE P2P — Auditoria BYOK de Pagamentos do Vendedor

Status: plano de implementação; nenhum item deste documento deve ser tratado como landed.

## 1. Objetivo e limites

O vendedor recebe BRL diretamente em sua própria conta Mercado Pago, Asaas ou Efí. A Kerosene:

- bloqueia os BTC do vendedor antes de exibir as instruções de pagamento;
- consulta o provedor com credenciais fornecidas pelo vendedor (BYOK);
- confirma conta, valor, moeda, referência, estado e unicidade da transação;
- autoriza o Vault Mesh a liberar os BTC ao comprador;
- nunca recebe, converte, transfere ou mantém BRL;
- não cria saque, estorno, transferência ou pagamento no provedor;
- não entrega credenciais ou dados financeiros aos vaults.
- atribui ao vendedor o risco de revogar a própria credencial ou encerrar a conta durante uma
  negociação em que o comprador já informou pagamento.

MVP: Pix/transferência identificável. Cartões ficam fora do MVP por chargeback e assimetria com a
irreversibilidade do Bitcoin.

## 2. Provedores da primeira versão

| Provedor | Autenticação BYOK | Evidência primária | Uso no P2P |
|---|---|---|---|
| Mercado Pago | Access Token da aplicação do vendedor; OAuth próprio do vendedor quando aplicável | `GET /v1/payments/{id}` e `/v1/payments/search` | Pagamentos/ordens registrados no ecossistema Payments |
| Asaas | API Key da conta no header `access_token` | `GET /v3/payments` e `/v3/financialTransactions` | Cobranças, Pix creditado e conciliação |
| Efí | `Client_Id`, `Client_Secret`, OAuth2 e certificado mTLS P12/PEM | `GET /v2/pix/{e2eId}` e listagem de Pix recebidos | Confirmação Pix por `endToEndId` |

### 2.1 Mercado Pago

Fontes:

- OAuth: <https://www.mercadopago.com.br/developers/pt/reference/authentication/oauth/overview>
- busca: <https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro/search-payments/get>
- segurança: <https://www.mercadopago.com.br/developers/pt/reference>

Regras:

- o vendedor cria a conta e a aplicação no Mercado Pago;
- a Kerosene não mantém conta financeira no provedor;
- token sempre no header `Authorization: Bearer`, nunca em query string;
- preferir busca por `providerPaymentId` ou `external_reference`;
- validar `live_mode`, recebedor, valor, moeda, estado e datas;
- não assumir que Pix arbitrário enviado à chave da conta aparece em Payments: homologar cada origem;
- estados aceitos no MVP: apenas pagamento final aprovado/acreditado conforme resposta ativa da API.

Matriz obrigatória de homologação:

| Origem | Critério |
|---|---|
| pagamento com ID conhecido | deve ser recuperável por ID |
| cobrança com `external_reference` | deve ser recuperável sem ambiguidade |
| link criado pelo vendedor | validar se aparece na API |
| QR/Pix do aplicativo | validar se aparece na API |
| Pix direto para chave | não habilitar até provar consulta confiável |

### 2.2 Asaas

Fontes:

- autenticação: <https://docs.asaas.com/docs/autentica%C3%A7%C3%A3o>
- cobranças: <https://docs.asaas.com/reference/listar-cobrancas>
- extrato: <https://docs.asaas.com/reference/recuperar-extrato>

Regras:

- o vendedor gera uma API Key própria;
- usar header `access_token` e `User-Agent` identificável, sem segredos;
- cobrança deve carregar `externalReference` opaca da negociação quando criada pelo vendedor;
- `GET /v3/payments` confirma o recurso individual;
- `GET /v3/financialTransactions` confirma impacto no saldo;
- correlacionar `PAYMENT_RECEIVED` ou `PIX_TRANSACTION_CREDIT` com o recurso detalhado;
- detectar `PAYMENT_REVERSAL`, estorno e demais débitos relacionados;
- a documentação desaconselha polling contínuo de `/payments`; usar polling limitado por negociação
  e reconciliação periódica, respeitando rate limits.

### 2.3 Efí

Fontes:

- credenciais e certificado: <https://dev.efipay.com.br/docs/api-pix/credenciais/>
- gestão de Pix: <https://dev.efipay.com.br/docs/api-pix/gestao-de-pix/>

Regras:

- vendedor fornece aplicação Efí, `Client_Id`, `Client_Secret` e certificado com chave privada;
- certificado mTLS é obrigatório inclusive em `POST /oauth/token`;
- habilitar somente escopos de consulta necessários quando configuráveis;
- usar `GET /v2/pix/{e2eId}` como confirmação preferencial;
- validar `endToEndId`, valor, horário, chave recebedora vinculada e devoluções;
- o `endToEndId` completo permanece apenas no domínio de evidência; demais serviços recebem HMAC;
- certificado e segredo têm envelopes e rotações independentes.

## 3. Arquitetura

Código de negócio no repositório `kerosene-kfe`:

```text
com.kerosene.kfe.p2p
├── controller
├── dto
├── model
├── repository
├── service
├── escrow
├── audit
├── credential
└── provider
    ├── mercadopago
    ├── asaas
    └── efi
```

Componentes:

```text
Cliente/Vendedor
      │ Onion
      ▼
P2pTradeService ──► EscrowLedger ──► VaultMeshSettlementPort
      │
      ▼
PaymentAuditQueue
      │
      ▼
PaymentAuditService ── TLS ponta a ponta ──► Egress Relay ──► Provedor
      │
      ▼
PaymentAttestationVerifier ──► ReleaseCoordinator ──► Vault Mesh
```

O `PaymentAuditService` é um domínio isolado:

- sem endpoint público;
- sem share FROST, chave Bitcoin ou acesso direto ao Vault Mesh;
- entrada apenas por jobs tipados;
- banco de credenciais separado;
- saída apenas por relays Onion;
- adaptadores com host, método e path fixos;
- sem URL arbitrária, redirect automático ou acesso a IP privado.

## 4. Egress privado

O provedor sempre verá um IP. Ele deve ver apenas o IP de um relay descartável, nunca o endereço do
core Kerosene.

```text
PaymentAuditService
      │ conexão ao endereço .onion do relay
      ▼
Relay BR-1/BR-2/BR-3
      │ TCP 443
      ▼
API oficial do provedor
```

Requisitos:

- TLS inicia no auditor e termina no provedor; relay não termina TLS;
- relay não armazena token, certificado, request ou response;
- allowlist por provedor e porta 443;
- entrada somente por Onion e autorização de uso único;
- nenhum proxy público;
- core sem rota direta para internet e sem fallback clearnet;
- DNS resolvido sem vazar pelo core;
- conta do vendedor mantém relay preferencial estável para reduzir alertas antifraude;
- falha de relay produz `VERIFICATION_UNAVAILABLE`, nunca liberação ou rejeição.

Antes do go-live, homologar Tor/relay e política antifraude com cada provedor. Compatibilidade técnica
não garante aceitação contratual.

## 5. Credenciais e minimização de dados

### 5.1 Entrada

O cliente cifra o pacote para uma chave pública exclusiva do domínio auditor antes de enviá-lo pelo
Onion. O serviço de entrada persiste o envelope sem ecoar o segredo.

```text
CredentialEnvelope
- version
- credentialId
- sellerUserId
- provider
- environment
- keyId
- ciphertext
- nonce
- aadHash
- createdAt
- expiresAt
```

AAD obrigatório: `credentialId`, `sellerUserId`, `provider`, `environment`, `version`.

### 5.2 Uso

- descriptografar somente em memória e durante um job;
- nunca colocar segredo em URL, fila, log, trace, métrica, argumento de processo ou erro;
- separar envelopes de token, refresh token, client secret, certificado e chave privada;
- suportar teste, rotação, revogação e expiração;
- registrar apenas fingerprint HMAC da credencial;
- apagar payload bruto após normalização, salvo evidência cifrada com retenção definida.

### 5.3 Limite de capacidade

Credenciais podem ser amplas, mas os adaptadores Kerosene aceitam somente operações de leitura
compiladas no código. O auditor bloqueia `POST`, `PUT`, `PATCH` e `DELETE`, exceto `POST /oauth/token`
necessário para renovar autenticação. Esse endpoint tem request fixo e não aceita parâmetros do
usuário.

## 6. Modelo de domínio

### SellerProviderAccount

```text
id, sellerUserId, provider, connectionMode, externalAccountFingerprint,
displayNameMasked, country, currency, status, credentialRef, capabilities,
preferredRelayId, lastVerifiedAt, createdAt, updatedAt
```

### P2pOrder

```text
id, publicId, buyerUserId, sellerUserId, sellerProviderAccountId,
fiatAmountMinor, fiatCurrency, cryptoAmountSats, priceSnapshotHash,
paymentReference, escrowIntentId, sellerAccessPolicyVersion,
sellerAccessPolicyHash, sellerAccessPolicyAcceptedAt, status, expiresAt,
createdAt, updatedAt
```

Estados:

```text
DRAFT
ESCROW_LOCK_PENDING
ESCROW_LOCKED
AWAITING_BUYER_PAYMENT
PAYMENT_REPORTED
PROVIDER_VERIFICATION_PENDING
PROVIDER_PAYMENT_CONFIRMED
PROVIDER_PAYMENT_MISMATCH
VERIFICATION_UNAVAILABLE
SELLER_ACCESS_REVOKED
SELLER_ACCOUNT_CLOSED
CREDENTIAL_RECOVERY_WINDOW
CREDENTIAL_RESTORED
RECOVERY_VALIDATION_PENDING
SELLER_RECOVERY_EXPIRED
RELEASE_PENDING
CRYPTO_RELEASED
AUTO_RELEASED_BY_SELLER_ACCESS_POLICY
EXPIRED
CANCELLED
DISPUTED
REQUIRES_RECONCILIATION
```

### P2pProviderPayment

```text
id, p2pOrderId, provider, providerTransactionFingerprint,
providerResourceType, providerStatusRaw, normalizedStatus,
amountMinor, currency, sellerAccountFingerprint, paymentMethod,
paidAt, evidenceHash, encryptedEvidenceRef, createdAt, updatedAt
```

Constraint global: `UNIQUE(provider, provider_transaction_fingerprint)`.

### P2pProviderReconciliationJob

```text
id, provider, sellerProviderAccountId, p2pOrderId, nextRunAt,
attempts, lastErrorCode, status, leaseOwner, leaseUntil
```

## 7. Porta comum

```text
SellerPaymentProviderAdapter
- validateCredential(credentialRef)
- identifyAccount(credentialRef)
- capabilities()
- findTransaction(PaymentLookup)
- getTransaction(ProviderTransactionRef)
- normalize(ProviderResponse)
- detectReversal(ProviderTransactionRef)
- refreshCredential(credentialRef)
```

`PaymentLookup` nunca contém URL ou query livre. Campos permitidos:

```text
providerPaymentId, externalReference, endToEndId,
expectedAmountMinor, currency, earliestAt, latestAt
```

Estado normalizado:

```text
NOT_FOUND, PENDING, APPROVED, SETTLED, REJECTED,
CANCELLED, REFUNDED, CHARGEBACK, REVERSED, UNKNOWN
```

## 8. Invariantes de confirmação

Uma ordem só muda para `PROVIDER_PAYMENT_CONFIRMED` quando:

1. escrow Bitcoin está `ESCROW_LOCKED`;
2. credencial pertence à conta cadastrada do vendedor;
3. transação pertence ao recurso/referência esperada;
4. recebedor coincide com `sellerAccountFingerprint`;
5. moeda é BRL e valor bruto corresponde exatamente à ordem;
6. status ativo está em allowlist específica do provedor;
7. data está dentro da janela da ordem;
8. transação não está cancelada, devolvida, contestada ou revertida;
9. fingerprint da transação não foi consumida por outra ordem;
10. resposta veio de consulta TLS ativa ao provedor;
11. evidência normalizada foi assinada pelo domínio auditor.

Saldo total, screenshot, PDF, texto do comprador ou retorno do aplicativo nunca confirmam pagamento.

## 9. Política de perda de acesso pelo vendedor

Regra aceita pelo vendedor antes de publicar a oferta:

> Se uma credencial previamente válida for revogada, substituída ou perder acesso por encerramento da
> conta depois que o comprador informar o pagamento, o vendedor terá 10 minutos para restaurar a
> verificação da mesma conta. Se não restaurar, os BTC bloqueados serão liberados ao comprador.

Essa regra não se aplica a indisponibilidade externa.

### 9.1 Condições para iniciar o timer

Todas devem ser verdadeiras:

1. credencial e conta foram verificadas imediatamente antes de bloquear o escrow;
2. instrução fiat foi exibida somente depois dessa verificação;
3. comprador marcou `PAYMENT_REPORTED` dentro do prazo;
4. comprador informou `providerPaymentId`, `externalReference` ou `endToEndId`;
5. perda de acesso ocorreu após o início da ordem;
6. erro foi classificado como revogação/encerramento, não falha de infraestrutura;
7. destino Bitcoin do comprador já estava imutavelmente vinculado ao escrow;
8. ordem contém versão, hash, data e assinatura de aceite da política.

Revogação confirmável:

- token inválido/revogado após tentativas independentes;
- OAuth `invalid_grant` após tentativa de refresh;
- API Key apagada ou substituída;
- certificado/credencial mTLS revogado;
- conta encerrada ou autorização removida conforme erro documentado pelo provedor;
- tentativa do vendedor de remover a última credencial vinculada a escrow ativo.

Não inicia timer:

- timeout;
- HTTP 429 ou 5xx;
- Tor, DNS ou relay indisponível;
- falha TLS;
- manutenção do provedor;
- resposta ambígua ou erro não classificado.

Esses casos permanecem em `VERIFICATION_UNAVAILABLE` e mantêm os BTC bloqueados.

### 9.2 Confirmação da revogação

Um único erro não basta. Antes de entrar em `CREDENTIAL_RECOVERY_WINDOW`, o auditor deve:

1. repetir a consulta;
2. tentar outro relay;
3. renovar o token quando houver refresh token;
4. consultar o endpoint de identidade/saúde da conta quando disponível;
5. persistir códigos redigidos e evidence hash;
6. obter a mesma conclusão conforme política específica do adaptador.

### 9.3 Recuperação

Durante 10 minutos o vendedor pode substituir token, API Key, segredo ou certificado. A nova
credencial deve identificar a mesma conta:

```text
newAccountFingerprint == escrowSellerAccountFingerprint
```

Restaurar a credencial não cancela nem libera a ordem. O estado muda para
`RECOVERY_VALIDATION_PENDING` e o auditor consulta a transação:

```text
confirmada      -> PROVIDER_PAYMENT_CONFIRMED -> release normal
não encontrada -> DISPUTED
inconclusiva    -> VERIFICATION_UNAVAILABLE/DISPUTED
```

### 9.4 Expiração e release por política

Se os 10 minutos expirarem sem restauração válida:

```text
CREDENTIAL_RECOVERY_WINDOW
        -> SELLER_RECOVERY_EXPIRED
        -> RELEASE_PENDING
        -> AUTO_RELEASED_BY_SELLER_ACCESS_POLICY
```

Esse release:

- usa o destino Bitcoin fixado antes do pagamento;
- consome o mesmo anti-replay e quorum de um release normal;
- registra motivo `SELLER_ACCESS_POLICY_RELEASE`;
- inclui `sellerAccessPolicyHash` no `ReleaseIntent`;
- não afirma que o provedor confirmou o pagamento;
- não pode ser interrompido apenas pelo botão de contestação;
- só é pausado se evidência técnica reclassificar o erro como indisponibilidade externa.

Após `PAYMENT_REPORTED`, vendedor não pode cancelar a ordem, remover a última credencial, trocar a
conta recebedora ou obter desbloqueio por expiração.

### 9.5 UX obrigatória

Antes de publicar a oferta, mostrar e exigir aceite assinado:

```text
Durante uma negociação ativa, mantenha sua conta e credencial acessíveis.
Se você revogar o acesso ou encerrar a conta após o comprador informar o
pagamento, terá 10 minutos para restaurar a verificação. Caso contrário,
os BTC bloqueados serão enviados ao comprador.
```

Durante a recuperação, mostrar contagem regressiva, motivo, conta mascarada e ação
`Atualizar credencial`. A UI não pode chamar indisponibilidade externa de revogação.

## 10. Fluxo P2P

```text
1. vendedor cadastra credencial BYOK;
2. auditor identifica conta e capacidades;
3. vendedor publica oferta;
4. comprador aceita;
5. KFE bloqueia BTC no bucket/escrow;
6. somente após Receipt de lock, exibe instrução fiat;
7. comprador paga diretamente ao vendedor;
8. comprador informa payment ID, referência ou e2eId;
9. auditor consulta provedor por relay;
10. pagamento confirmado segue release normal;
11. acesso revogado segue a janela da política da seção 9;
12. KFE cria ReleaseIntent idempotente com motivo e evidence hash;
13. Vault Mesh libera BTC ao comprador;
14. reconciliação posterior detecta reversão e aciona risco/disputa, sem tentar desfazer Bitcoin.
```

O Vault Mesh recebe apenas:

```text
tradeId, escrowIntentId, destination, amountSats,
releaseReason, evidenceHash, sellerAccessPolicyHash, releaseAuthorization
```

## 11. Polling e webhook

MVP usa polling porque a Kerosene não expõe endpoint clearnet:

```text
0s, 5s, 15s, 30s, 60s, depois backoff limitado por provedor
```

Regras:

- respeitar `Retry-After`, rate limits e recomendações do provedor;
- lookup direto por ID tem prioridade sobre listagem;
- Asaas `/payments` não recebe polling contínuo;
- `UNKNOWN`, timeout ou 429 nunca significam pagamento ausente;
- circuit breaker isolado por provedor e relay;
- webhook futuro passa por relay público sem credencial, valida assinatura e apenas agenda consulta;
- webhook nunca autoriza release sozinho.

## 12. APIs KFE propostas

```text
POST   /kfe/p2p/seller/provider-accounts
POST   /kfe/p2p/seller/provider-accounts/{id}/test
POST   /kfe/p2p/seller/provider-accounts/{id}/rotate
DELETE /kfe/p2p/seller/provider-accounts/{id}

POST   /kfe/p2p/offers
POST   /kfe/p2p/orders
GET    /kfe/p2p/orders/{publicId}
POST   /kfe/p2p/orders/{publicId}/payment-reported
POST   /kfe/p2p/orders/{publicId}/cancel
POST   /kfe/p2p/orders/{publicId}/dispute
POST   /kfe/p2p/orders/{publicId}/seller-access/recover
```

Nenhuma resposta retorna credencial, CPF completo, ID financeiro completo ou evidência bruta.

## 13. Fases

### Fase 0 — Contratos e threat model

- congelar estados, invariantes, evidência canônica e política de retenção;
- definir fraude, replay, pagamento ambíguo, provedor indisponível e reversão;
- versionar e congelar a política de release após 10 minutos de revogação;
- confirmar contrato comercial/API para contexto P2P Bitcoin;
- definir que somente Pix entra no MVP.

Critério: revisão de segurança aprovada e nenhuma transição permite release sem lock e attestation.

### Fase 1 — Core provider-agnostic

- migrations, entidades, repositories e state machine;
- `SellerPaymentProviderAdapter`;
- credential references, HMAC de identificadores e evidência cifrada;
- fila/outbox e leases de reconciliação;
- adaptador fake determinístico.

Critério: testes de concorrência provam lock único, consumo único e release idempotente.

### Fase 2 — Credential Vault e relays

- envelope por vendedor/provedor;
- rotação, revogação e zeroização best-effort;
- três relays Onion sem término TLS;
- egress fail-closed e allowlists;
- métricas sem dados sensíveis.

Critério: teste de vazamento demonstra que falha do Tor/relay não produz conexão direta.

### Fase 3 — Efí Pix

- OAuth2 client credentials com mTLS;
- lookup por `endToEndId`;
- detecção de devolução;
- homologação de certificado e rotação.

Critério: Pix real de baixo valor confirma uma única ordem e e2eId repetido é rejeitado.

### Fase 4 — Mercado Pago

- Access Token BYOK e identificação da conta;
- lookup por ID e `external_reference`;
- matriz de homologação por origem;
- normalização de aprovado, cancelado, devolvido e contestado.

Critério: somente origens comprovadamente visíveis na API são anunciadas na UI.

### Fase 5 — Asaas

- API Key BYOK;
- cobrança por `externalReference`;
- consulta detalhada mais extrato;
- reconciliação de tarifa, reversão e Pix creditado.

Critério: confirmação usa recurso detalhado e impacto financeiro correlacionados, sem polling abusivo.

### Fase 6 — Escrow/Vault Mesh

- lock antes das instruções fiat;
- `ReleaseIntent` com attestation hash;
- `ReleaseIntent` com policy hash e motivo distinto para release por revogação;
- intent consume e anti-replay;
- expiração nunca desbloqueia quando pagamento está `UNKNOWN`;
- timer durável de 10 minutos, retomável após restart e baseado em horário monotônico/servidor;
- bloqueio de cancelamento e remoção da credencial após `PAYMENT_REPORTED`;
- disputa e reconciliação manual.

Critério: testes E2E provam que release ocorre somente por pagamento confirmado ou pela política de
revogação aceita; comprador não consegue iniciar a política sem perda de acesso atribuível ao vendedor.

### Fase 7 — Operação e rollout

- limites por vendedor, conta, ordem e período;
- alertas para relay, token, certificado, 401/403/429 e backlog;
- rollout Efí → Mercado Pago → Asaas;
- feature flag por provedor, origem e vendedor;
- kill switch que bloqueia novos negócios sem afetar escrows existentes.

Critério: go-live somente com reconciliação, runbook de incidente e recuperação de credencial testados.

## 14. Testes obrigatórios

- credencial inválida, expirada, revogada e trocada entre vendedores;
- credencial válida ao abrir e revogada antes/depois de `PAYMENT_REPORTED`;
- 401 único não inicia timer; revogação confirmada por segundo relay e refresh inválido inicia;
- timeout, 429, 5xx, Tor, DNS, TLS e relay nunca iniciam auto-release;
- recuperação no segundo 599 consulta a transação antes de decidir;
- restart durante a janela preserva deadline e não reinicia os 10 minutos;
- nova credencial de outra conta é rejeitada;
- vendedor não cancela, troca conta ou remove última credencial após relato de pagamento;
- expiração da janela libera somente para destino Bitcoin previamente fixado;
- release por política registra motivo e não se apresenta como pagamento confirmado;
- relay indisponível, TLS inválido, DNS leak e tentativa de egress direto;
- resposta truncada, grande, lenta e fora de ordem;
- mesmo pagamento em duas ordens concorrentes;
- valor menor, maior, moeda e conta divergentes;
- pagamento após expiração;
- status aprovado seguido de devolução/chargeback;
- lock falha antes de instrução fiat;
- release falha após confirmação e é retomado idempotentemente;
- crash entre confirmação, consumo da transação e ReleaseIntent;
- logs, traces, métricas, filas e erros sem segredos ou PII;
- migrations e índices sob concorrência realista.

## 15. Bloqueadores de go-live

- compatibilidade contratual dos provedores com o caso P2P/Bitcoin;
- prova de que as origens anunciadas são consultáveis;
- relays estáveis sem exposição do core;
- cofre de credenciais real e restauração segura;
- escrow Bitcoin efetivo, não apenas status em banco;
- política para MED, devolução, fraude e disputa;
- texto e aceite explícito da política de revogação, com versionamento imutável;
- classificação testada de erros de autenticação para cada provedor;
- LGPD: base, minimização, retenção e direitos do titular;
- avaliação jurídica do papel da Kerosene na intermediação P2P.

## 16. Decisão

Implementar um core único e três adaptadores. Efí é a primeira integração por oferecer evidência Pix
forte via `endToEndId`; Mercado Pago entra após homologar quais origens são visíveis; Asaas entra com
consulta de cobrança mais extrato. Nenhum provedor, relay ou credencial toca no Vault Mesh.
