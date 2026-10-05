# mTLS SPIFFE entre Auth e KFE

Quando a identidade de workload está ativa, o KFE expõe rotas financeiras e
internas somente pela porta TLS 1.3 dedicada. A porta pública mantém health,
mas rejeita `/kfe/**`, `/api/admin/kfe/**`, `/api/public/kfe/**` e
`/internal/kfe/**` antes de chegar aos controllers.
O tráfego cliente desses três namespaces entra primeiro pelo gateway do Auth;
o KFE o aceita somente no conector interno e com o SVID exato do Auth.

O KFE prova `spiffe://<dominio-de-confianca>/service/kfe` e aceita somente
`spiffe://<dominio-de-confianca>/service/auth`. Callbacks para Auth usam o mesmo
X.509-SVID rotativo e validam o par exato. O URI SAN substitui a comparação por
DNS; cadeia, bundle atual e SPIFFE ID continuam sendo validados.

Configuração obrigatória em produção:

- `KEROSENE_WORKLOAD_IDENTITY_ENABLED=true`
- `SPIFFE_ENDPOINT_SOCKET=unix:///spiffe-workload-api/spire-agent.sock`
- `KEROSENE_OWN_SPIFFE_ID=spiffe://<dominio-de-confianca>/service/kfe`
- `KEROSENE_PEER_SPIFFE_ID=spiffe://<dominio-de-confianca>/service/auth`
- `KEROSENE_INTERNAL_MTLS_PORT=8443`
- `AUTH_REMOTE_BASE_URL=https://server:8443`
- `KFE_INTERNAL_SHARED_SECRET` ausente
- `KFE_COLUMN_CRYPTO_KEY_BASE64` configurada separadamente
- `KFE_FEE_QUOTE_SIGNING_SECRET` configurado separadamente

Os profiles Spring `prod` ou `production` sempre ativam o gate de produção; uma
flag de ambiente não consegue desligá-lo. Produção recusa boot se alguma regra for enfraquecida. A ativação
continua bloqueada até o preflight e um teste Auth↔KFE real provarem rotação e
rejeição de SVID ausente, incorreto e expirado.
