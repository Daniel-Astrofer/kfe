package com.kerosene.kfe.application.transaction;

import java.util.List;
import java.util.Set;

/**
 * Canonical movement types that credit the AVAILABLE bucket (or system profit).
 * Used for dual-path guards and partial unique index on {@code balance_movements}.
 */
public final class KfeLedgerMovementTypes {

    public static final String CREDIT_INBOUND = "CREDIT_INBOUND";
    public static final String CREDIT_PAYMENT_REQUEST = "CREDIT_PAYMENT_REQUEST";
    public static final String CREDIT_CUSTODIAL_DEPOSIT = "CREDIT_CUSTODIAL_DEPOSIT";
    public static final String CREDIT = "CREDIT";
    public static final String CREDIT_KEROSENE_FEE = "CREDIT_KEROSENE_FEE";

    /** Types protected by unique (transaction_id, movement_type) for available-side credits. */
    public static final List<String> IDEMPOTENT_CREDIT_TYPES = List.of(
            CREDIT_INBOUND,
            CREDIT_PAYMENT_REQUEST,
            CREDIT_CUSTODIAL_DEPOSIT,
            CREDIT,
            CREDIT_KEROSENE_FEE);

    // ITEM 12: Reversal/correction movement types (create compensating movements, never UPDATE/DELETE)
    public static final String REVERSAL_CREDIT = "REVERSAL_CREDIT";
    public static final String REVERSAL_DEBIT = "REVERSAL_DEBIT";
    public static final String CORRECTION_CREDIT = "CORRECTION_CREDIT";
    public static final String CORRECTION_DEBIT = "CORRECTION_DEBIT";

    /** User wallet available credits (excludes system fee). */
    public static final List<String> USER_AVAILABLE_CREDIT_TYPES = List.of(
            CREDIT_INBOUND,
            CREDIT_PAYMENT_REQUEST,
            CREDIT_CUSTODIAL_DEPOSIT,
            CREDIT);

    public static final Set<String> IDEMPOTENT_CREDIT_TYPE_SET = Set.copyOf(IDEMPOTENT_CREDIT_TYPES);

    private KfeLedgerMovementTypes() {
    }

    public static boolean isIdempotentCreditType(String movementType) {
        return movementType != null && IDEMPOTENT_CREDIT_TYPE_SET.contains(movementType);
    }
}
