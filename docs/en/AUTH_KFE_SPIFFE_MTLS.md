# Auth/KFE SPIFFE mTLS

KFE exposes business and internal routes only through its dedicated internal
TLS 1.3 connector when workload identity is enabled. The public connector keeps
health endpoints available but rejects `/kfe/**`, `/api/admin/kfe/**`,
`/api/public/kfe/**` and `/internal/kfe/**` before controller dispatch.
Client traffic for all three public KFE namespaces first enters the Auth
gateway. KFE accepts it only on the internal connector from the exact Auth SVID.

KFE proves `spiffe://<trust-domain>/service/kfe` and accepts only
`spiffe://<trust-domain>/service/auth`. Outbound callbacks to Auth use the same
rotating X.509-SVID and exact peer check. URI SAN identity replaces DNS SAN
matching; the dedicated client still validates the SVID chain and the exact
SPIFFE ID against the current Workload API bundle.

Required production settings:

- `KEROSENE_WORKLOAD_IDENTITY_ENABLED=true`
- `SPIFFE_ENDPOINT_SOCKET=unix:///spiffe-workload-api/spire-agent.sock`
- `KEROSENE_OWN_SPIFFE_ID=spiffe://<trust-domain>/service/kfe`
- `KEROSENE_PEER_SPIFFE_ID=spiffe://<trust-domain>/service/auth`
- `KEROSENE_INTERNAL_MTLS_PORT=8443`
- `AUTH_REMOTE_BASE_URL=https://server:8443`
- `KFE_INTERNAL_SHARED_SECRET` unset
- `KFE_COLUMN_CRYPTO_KEY_BASE64` set independently
- `KFE_FEE_QUOTE_SIGNING_SECRET` set independently

The `prod` or `production` Spring profile always enables the production gate;
an environment flag cannot disable it. Production refuses boot if any identity rule is weakened. Activation is
still blocked until deploy preflight and a real Auth↔KFE test prove successful
rotation and rejection of missing, wrong and expired SVIDs.
