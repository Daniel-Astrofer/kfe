package com.kerosene.kfe.application.settlement;

/**
 * Binary settlement flags (doc: Motor KFE — liquidação binária).
 * Each flag is 0 (fail) or 1 (pass). Final result is logical AND of all flags.
 */
public enum SettlementFlag {
    V_IDEMPOTENCIA,
    V_LOCK_BANDO,
    V_ATOMICIDADE,
    V_SALDO_DISP,
    V_DINHEIRO_REAL,
    V_LIQUIDEZ,
    V_P2P,
    V_ASSINATURA_MPC,
    V_RESERVA_MAT,
    V_NO_JAMMING,
    V_CIRCUIT_BREAKER
}
