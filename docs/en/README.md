# KFE service

Build: `./gradlew check`. Runtime entrypoint:
`com.kerosene.kfe.runtime.KfeServiceApplication`.

Dependencies: canonical Contracts and internal Shared composite builds. Vault,
Node and rail integrations remain external. Deploy owns images and runtime
configuration.

Auth no longer consumes the KFE implementation artifact. Pending: publish
immutable contract versions and maintain remote compatibility tests. mTLS and
CometBFT are not part of this migration.

Reference: [Kerosene Node integration](../reference/KEROSENE_NODE_INTEGRATION.md).
