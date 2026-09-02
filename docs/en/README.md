# KFE service

Build: `./gradlew check`. Runtime entrypoint:
`com.kerosene.kfe.runtime.KfeServiceApplication`.

Dependencies: canonical Contracts and internal Shared composite builds. Vault,
Node and rail integrations remain external. Deploy owns images and runtime
configuration.

Auth no longer consumes the KFE implementation artifact. Native SPIFFE mTLS is
implemented for the Auth/KFE boundary; cluster activation and negative
handshake evidence remain pending. CometBFT membership is a separate stage.

Security: [Auth/KFE SPIFFE mTLS](AUTH_KFE_SPIFFE_MTLS.md).

Reference: [Kerosene Node integration](../reference/KEROSENE_NODE_INTEGRATION.md).
