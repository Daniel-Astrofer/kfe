# Kerosene Node integration

The KFE uses `kerosene-node` as an authorization directory for Vault onion
hosts. Its existing Vault client still owns financial mTLS, request
authentication and operation-specific quorum behavior.

Enable the bridge with:

| Variable | Meaning |
| --- | --- |
| `KFE_KEROSENE_NODE_ENABLED` | Enables fail-closed directory authorization |
| `KFE_KEROSENE_NODE_BASE_URL` | Vault-plane Node HTTPS onion URL |
| `KFE_KEROSENE_NODE_NETWORK_ID` | Exact expected network ID |
| `KFE_KEROSENE_NODE_TLS_CERT_PATH` | Client certificate for Node mTLS |
| `KFE_KEROSENE_NODE_TLS_KEY_PATH` | Client private key for Node mTLS |
| `KFE_KEROSENE_NODE_TLS_CA_PATH` | CA used to verify the Node |
| `KFE_KEROSENE_NODE_TRANSPORT` | Must remain `tor` in production-like environments |
| `KFE_KEROSENE_NODE_SOCKS_HOST` | Tor SOCKS proxy host |
| `KFE_KEROSENE_NODE_SOCKS_PORT` | Tor SOCKS proxy port |
| `KFE_KEROSENE_NODE_CACHE_TTL_MS` | Verified roster refresh interval |

For every financial call, the KFE verifies that each configured Vault URL uses
HTTPS and a v3 onion hostname present in the current signed Vault-plane
membership manifest. Service ports remain local policy and are not copied from
an unsigned directory.

The KFE rejects an unavailable Node, an empty roster, a network/plane mismatch,
clearnet endpoints and Vault onion hosts outside the verified roster. The Node
does not receive ledger data, FROST material, Vault credentials or authority to
activate a signer.

Keep the feature disabled during the first bootstrap before a signed Vault
manifest exists. Enable it only after the Node is reachable through Tor and the
statically configured Vault URLs use hosts present in that manifest.
