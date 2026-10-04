package com.kerosene.kfe.paymentexecution.domain.model;

public record SettlementJammingCheck(boolean allowed, boolean hardBlock, String reason) {}
