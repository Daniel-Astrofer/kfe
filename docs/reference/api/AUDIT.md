# Auditoria API

Documentação corporativa dos endpoints de auditoria disponíveis no backend atual.

Fonte real inspecionada:

- `src/main/java/com/kerosene/kfe/controller/KfeAuditAdminController.java`
- `src/main/java/com/kerosene/kfe/dto/KfeAuditEventResponse.java`
- `src/main/java/com/kerosene/kfe/dto/KfeAuditLatestResponse.java`
- `src/main/java/com/kerosene/kfe/dto/KfeAuditRootResponse.java`
- `kerosene-core/auth-service/src/main/java/com/kerosene/common/security/EndpointPolicyRegistry.java`

## Estado real do serviço

A documentação anterior citava `LedgerAuditController` e `MerkleAuditController`, mas esses controllers não existem no código-fonte atual em `src/main/java`. A API de auditoria ativa confirmada é a auditoria KFE em:

```text
/api/admin/kfe/audit/**
```

Ela está protegida por duas camadas:

1. `EndpointPolicyRegistry`: `/api/admin/**` exige política `ADMIN`.
2. `KfeAuditAdminController`: `@PreAuthorize("hasRole('ADMIN')")`.

## Headers comuns

| Nome | Tipo | Obrigatório | Descrição | Exemplo |
| --- | --- | --- | --- | --- |
| `Authorization` | string | Sim | JWT Bearer com role `ADMIN`. | `Bearer <ADMIN_JWT>` |
| `Accept` | string | Opcional | Preferencialmente JSON. | `application/json` |
| `X-Correlation-Id` | string | Recomendado | Rastreabilidade entre gateway, backend e logs. | `audit-20260619-0001` |

## Envelope de resposta

Todos os endpoints ativos de auditoria KFE retornam `ApiResponse<T>`:

```json
{
  "success": true,
  "message": "KFE audit events retrieved.",
  "data": {},
  "timestamp": "2026-06-19T12:00:00"
}
```

| Campo | Tipo | Descrição |
| --- | --- | --- |
| `success` | boolean | `true` em sucesso. |
| `message` | string | Mensagem operacional definida pelo controller. |
| `data` | object/array | Payload tipado do endpoint. |
| `timestamp` | string datetime | Momento de montagem do envelope. |

## Endpoint: Obter último estado de auditoria

```http
GET /api/admin/kfe/audit/latest
```

### O que faz

Retorna o último evento auditável conhecido e a raiz Merkle atual da cadeia de eventos KFE.

### Quando usar

- Painel administrativo de integridade.
- Verificação rápida antes de publicar prova de auditoria.
- Diagnóstico de cadeia de hash/Merkle.

### Request

Não recebe path parameters, query parameters ou body.

### Exemplo curl

```bash
curl -X GET "$BASE_URL/api/admin/kfe/audit/latest" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Accept: application/json"
```

### Response de sucesso

Status: `200 OK`

```json
{
  "success": true,
  "message": "KFE audit latest root retrieved.",
  "data": {
    "latestEvent": {
      "sequenceNumber": 981,
      "id": "018f5d42-7b46-7d9f-9a1b-c405c8d6e001",
      "transactionId": "018f5d42-7b46-7d9f-9a1b-c405c8d6e010",
      "walletId": "018f5d42-7b46-7d9f-9a1b-c405c8d6e020",
      "eventType": "TRANSACTION_STATUS_CHANGED",
      "fromStatus": "PENDING",
      "toStatus": "COMPLETED",
      "payloadHash": "sha256:payload",
      "previousHash": "sha256:previous",
      "eventHash": "sha256:event",
      "createdAt": "2026-06-19T12:00:00"
    },
    "root": {
      "merkleRoot": "sha256:root",
      "eventCount": 981,
      "fromSequence": 1,
      "toSequence": 981,
      "generatedAt": "2026-06-19T12:00:00"
    }
  },
  "timestamp": "2026-06-19T12:00:00"
}
```

## Endpoint: Listar eventos de auditoria

```http
GET /api/admin/kfe/audit/events?limit=50
```

### O que faz

Lista eventos auditáveis KFE recentes.

### Query parameters

| Nome | Tipo | Obrigatório | Default | Descrição | Restrições |
| --- | --- | --- | --- | --- | --- |
| `limit` | integer | Não | `50` | Quantidade máxima de eventos retornados. | O service restringe para uma faixa segura; limite operacional confirmado até `500`. |

### Exemplo curl

```bash
curl -X GET "$BASE_URL/api/admin/kfe/audit/events?limit=100" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Accept: application/json"
```

### Response de sucesso

Status: `200 OK`

```json
{
  "success": true,
  "message": "KFE audit events retrieved.",
  "data": [
    {
      "sequenceNumber": 981,
      "id": "018f5d42-7b46-7d9f-9a1b-c405c8d6e001",
      "transactionId": "018f5d42-7b46-7d9f-9a1b-c405c8d6e010",
      "walletId": "018f5d42-7b46-7d9f-9a1b-c405c8d6e020",
      "eventType": "TRANSACTION_CREATED",
      "fromStatus": null,
      "toStatus": "PENDING",
      "payloadHash": "sha256:payload",
      "previousHash": "sha256:previous",
      "eventHash": "sha256:event",
      "createdAt": "2026-06-19T12:00:00"
    }
  ],
  "timestamp": "2026-06-19T12:00:00"
}
```

## Endpoint: Listar eventos por transação

```http
GET /api/admin/kfe/audit/transactions/{transactionId}
```

### Path parameters

| Nome | Tipo | Obrigatório | Descrição | Exemplo | Restrições |
| --- | --- | --- | --- | --- | --- |
| `transactionId` | UUID | Sim | Transação KFE cujos eventos serão consultados. | `018f5d42-7b46-7d9f-9a1b-c405c8d6e010` | UUID válido. |

### Exemplo curl

```bash
curl -X GET "$BASE_URL/api/admin/kfe/audit/transactions/$TRANSACTION_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Accept: application/json"
```

### Response de sucesso

Status: `200 OK`; `data` é uma lista de `KfeAuditEventResponse`, com os mesmos campos documentados em `/events`.

## Endpoint: Computar raiz Merkle

```http
POST /api/admin/kfe/audit/root
```

### O que faz

Calcula a raiz Merkle atual sobre a sequência de eventos auditáveis KFE.

### Request body

Não enviar body.

### Exemplo curl

```bash
curl -X POST "$BASE_URL/api/admin/kfe/audit/root" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Accept: application/json"
```

### Response de sucesso

Status: `200 OK`

```json
{
  "success": true,
  "message": "KFE audit root computed.",
  "data": {
    "merkleRoot": "sha256:root",
    "eventCount": 981,
    "fromSequence": 1,
    "toSequence": 981,
    "generatedAt": "2026-06-19T12:00:00"
  },
  "timestamp": "2026-06-19T12:00:00"
}
```

## Campos dos modelos

### `KfeAuditEventResponse`

| Campo | Tipo | Nullable | Descrição |
| --- | --- | --- | --- |
| `sequenceNumber` | long | Não | Sequência monotônica do evento. |
| `id` | UUID | Não | ID do evento. |
| `transactionId` | UUID | Sim | Transação relacionada, quando aplicável. |
| `walletId` | UUID | Sim | Carteira relacionada, quando aplicável. |
| `eventType` | string | Não | Tipo lógico do evento. |
| `fromStatus` | string | Sim | Status anterior. |
| `toStatus` | string | Sim | Status novo. |
| `payloadHash` | string | Não | Hash do payload auditado. |
| `previousHash` | string | Sim | Hash anterior na cadeia. |
| `eventHash` | string | Não | Hash do evento atual. |
| `createdAt` | datetime | Não | Data de criação. |

### `KfeAuditRootResponse`

| Campo | Tipo | Nullable | Descrição |
| --- | --- | --- | --- |
| `merkleRoot` | string | Sim | Raiz Merkle calculada. |
| `eventCount` | long | Não | Total de eventos incluídos. |
| `fromSequence` | long | Sim | Primeira sequência incluída. |
| `toSequence` | long | Sim | Última sequência incluída. |
| `generatedAt` | datetime | Não | Momento do cálculo. |

## Status codes

| Status | Quando ocorre | Como resolver | Exemplo de resposta |
| --- | --- | --- | --- |
| `200 OK` | Consulta ou cálculo concluído. | Consumir `data`. | `{ "success": true, "data": {} }` |
| `400 Bad Request` | `transactionId` inválido ou query malformada. | Corrigir UUID/query. | Varia conforme handler global. |
| `401 Unauthorized` | JWT ausente, inválido ou expirado. | Reautenticar. | Varia conforme Spring Security. |
| `403 Forbidden` | Token sem role `ADMIN`. | Usar usuário administrativo. | Varia conforme Spring Security. |
| `404 Not Found` | Transação sem eventos ou rota antiga inexistente. | Conferir ID/path. | Varia conforme handler global. |
| `429 Too Many Requests` | Rate limit global. | Aplicar backoff. | Varia conforme filtro global. |
| `500 Internal Server Error` | Falha ao consultar/gerar raiz. | Investigar logs com correlation id. | Varia conforme handler global. |

## Rotas legadas removidas

As rotas antigas `/audit/**` e `/v1/audit/**` citadas em versões anteriores dependiam de controllers que não existem mais no código-fonte atual. Trate-as como documentação stale até que controllers e policies sejam restaurados.
