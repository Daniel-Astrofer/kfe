# Serviço KFE

O KFE possui ledger, carteiras, reconciliação e execução financeira. Valide com
`./gradlew check`; a aplicação inicia por
`com.kerosene.kfe.runtime.KfeServiceApplication`.

Ele consome Contracts e Shared como builds irmãos. Vault, Node e Rails continuam
serviços externos. Imagens, configurações e ambientes pertencem ao Deploy.

O Auth não consome mais o artefato de implementação do KFE. Pendente: publicar
versões imutáveis dos contratos e manter testes de compatibilidade remota. mTLS
e CometBFT não fazem parte desta etapa.

Referência técnica: [integração com Kerosene Node](../reference/KEROSENE_NODE_INTEGRATION.md).
