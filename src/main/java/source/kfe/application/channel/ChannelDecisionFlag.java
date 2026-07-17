package source.kfe.application.channel;

/**
 * Binary flags for channel lifecycle decisions (architecture doc §3).
 */
public enum ChannelDecisionFlag {
    // OPEN
    V_CAPITAL_MINIMO,
    V_TAXA_ONCHAIN_BAIXA,
    V_SAIDA_ANCORA,
    V_AUTORIZACAO_MPC,
    V_DENYLIST_PEER,
    // REBALANCE
    V_LIMIAR_DRENAGEM,
    V_LUCRO_MATEMATICO,
    V_FUNDO_CORRETO,
    // CLOSE
    V_CANAL_MORTO,
    V_SEGURANCA_DE_FECHAMENTO,
    // PPM
    V_PPM_BELOW_BREAKEVEN,
    V_PPM_DRAIN_ALERT
}
