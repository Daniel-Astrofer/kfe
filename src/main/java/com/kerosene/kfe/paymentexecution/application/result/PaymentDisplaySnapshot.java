package com.kerosene.kfe.paymentexecution.application.result;

import java.math.BigDecimal;

/** Presentation-only values: never used for reservations, ledger movements, or settlement decisions. */
public record PaymentDisplaySnapshot(
        BigDecimal btcUsd,
        BigDecimal btcEur,
        BigDecimal btcBrl,
        BigDecimal amountUsd,
        BigDecimal amountEur,
        BigDecimal amountBrl) {
}
