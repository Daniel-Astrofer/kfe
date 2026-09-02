# Serviço KFE

O KFE possui ledger, carteiras, reconciliação e execução financeira. Valide com
`./gradlew check`; a aplicação inicia por
`com.kerosene.kfe.runtime.KfeServiceApplication`.

Ele consome Contracts e Shared como builds irmãos. Vault, Node e Rails continuam
serviços externos. Imagens, configurações e ambientes pertencem ao Deploy.

O Auth não consome mais o artefato de implementação do KFE. O mTLS SPIFFE
nativo dessa fronteira está implementado; ativação no cluster e evidências de
handshake negativo continuam pendentes. O membership CometBFT é outra etapa.

Segurança: [mTLS SPIFFE entre Auth e KFE](MTLS-SPIFFE-AUTH-KFE.md).

Referência técnica: [integração com Kerosene Node](../reference/KEROSENE_NODE_INTEGRATION.md).
