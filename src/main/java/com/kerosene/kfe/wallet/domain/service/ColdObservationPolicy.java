package com.kerosene.kfe.wallet.domain.service;

/** Confirmation policy shared by cold-wallet observers and confirmation refreshers. */
public final class ColdObservationPolicy {
    private ColdObservationPolicy() {
    }

    public static boolean isCreditFinal(int confirmations, int requiredConfirmations) {
        return Math.max(0, confirmations) >= Math.max(1, requiredConfirmations);
    }

    public static boolean mayAdvance(int currentConfirmations, int observedConfirmations) {
        return Math.max(0, observedConfirmations) >= Math.max(0, currentConfirmations);
    }
}
