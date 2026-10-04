package com.kerosene.kfe.paymentexecution.application.result;

import java.math.BigDecimal;

/** Raw rates are retained for the historical display snapshot, including unavailable/invalid values. */
public record PaymentDisplayRates(BigDecimal btcUsd, BigDecimal btcEur, BigDecimal btcBrl) {
}
