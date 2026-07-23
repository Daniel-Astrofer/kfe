package com.kerosene.kfe.model;

public enum KfeLiquidityReservationStatus {
    /** Capacity locked until Lightning payment resolves. */
    HELD,
    /** Payment failed / unlocked — capacity free again. */
    RELEASED,
    /** Payment succeeded — capacity left the node via HTLC. */
    CONSUMED
}
