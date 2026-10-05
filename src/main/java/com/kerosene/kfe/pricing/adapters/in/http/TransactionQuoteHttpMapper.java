package com.kerosene.kfe.pricing.adapters.in.http;

import com.kerosene.kfe.pricing.application.command.QuoteTransactionCommand;
import com.kerosene.kfe.pricing.application.result.FeeTierResult;
import com.kerosene.kfe.pricing.application.result.TransactionQuoteResult;

final class TransactionQuoteHttpMapper {

    private TransactionQuoteHttpMapper() {
    }

    static QuoteTransactionCommand toCommand(TransactionQuoteRequest request) {
        return new QuoteTransactionCommand(
                request.rail(),
                request.direction(),
                request.amountSats(),
                request.networkFeeSats());
    }

    static TransactionQuoteResponse toResponse(TransactionQuoteResult result) {
        return new TransactionQuoteResponse(
                result.rail(),
                result.direction(),
                result.grossAmountSats(),
                result.receiverAmountSats(),
                result.networkFeeSats(),
                result.totalDebitSats(),
                result.keroseneFeeSats(),
                result.totalFeeSats(),
                result.feeRateSatPerVbyte(),
                result.estimatedVbytes(),
                result.estimatedConfirmationBlocks(),
                result.estimatedSettlementSeconds(),
                result.feeSource(),
                result.quoteExpiresAt(),
                result.feeTiers().stream().map(TransactionQuoteHttpMapper::toResponse).toList());
    }

    private static FeeTierResponse toResponse(FeeTierResult tier) {
        return new FeeTierResponse(
                tier.name(),
                tier.feeRateSatPerVbyte(),
                tier.networkFeeSats(),
                tier.targetBlocks(),
                tier.estimatedSeconds(),
                tier.source());
    }
}
