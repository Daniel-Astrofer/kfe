package com.kerosene.kfe.pricing.application.usecase;

import com.kerosene.kfe.pricing.application.command.QuoteTransactionCommand;
import com.kerosene.kfe.pricing.application.port.in.QuoteTransactionUseCase;
import com.kerosene.kfe.pricing.application.port.out.NetworkFeePort;
import com.kerosene.kfe.pricing.application.port.out.PricingPolicyPort;
import com.kerosene.kfe.pricing.application.result.TransactionQuoteResult;
import com.kerosene.kfe.pricing.domain.model.PricingQuote;
import com.kerosene.kfe.pricing.domain.model.SatoshiAmount;
import com.kerosene.kfe.pricing.domain.service.PricingCalculator;

/** Coordinates fee estimation and the pure pricing policy. */
public final class QuoteTransactionService implements QuoteTransactionUseCase {

    private final NetworkFeePort networkFeePort;
    private final PricingPolicyPort pricingPolicyPort;
    private final PricingCalculator calculator;

    public QuoteTransactionService(
            NetworkFeePort networkFeePort,
            PricingPolicyPort pricingPolicyPort,
            PricingCalculator calculator) {
        this.networkFeePort = networkFeePort;
        this.pricingPolicyPort = pricingPolicyPort;
        this.calculator = calculator;
    }

    @Override
    public TransactionQuoteResult quote(QuoteTransactionCommand command) {
        NetworkFeePort.NetworkFeeEstimate feeEstimate = networkFeePort.estimate(
                command.rail(), command.direction(), command.requestedNetworkFeeSats());
        PricingQuote pricingQuote = calculator.quote(
                command.rail(),
                command.direction(),
                SatoshiAmount.positive(command.amountSats()),
                new SatoshiAmount(feeEstimate.selectedNetworkFeeSats()),
                pricingPolicyPort.snapshot(command.rail(), command.direction()));

        long totalFee = Math.addExact(
                pricingQuote.networkFee().value(),
                pricingQuote.keroseneFee().value());
        return new TransactionQuoteResult(
                command.rail(),
                command.direction(),
                pricingQuote.grossAmount().value(),
                pricingQuote.receiverAmount().value(),
                pricingQuote.networkFee().value(),
                pricingQuote.totalDebit().value(),
                pricingQuote.keroseneFee().value(),
                totalFee,
                feeEstimate.selectedFeeRateSatPerVbyte(),
                feeEstimate.estimatedVbytes(),
                feeEstimate.selectedTargetBlocks(),
                feeEstimate.selectedEstimatedSeconds(),
                feeEstimate.selectedSource(),
                feeEstimate.expiresAt(),
                feeEstimate.tiers(),
                pricingQuote.pricingPolicyVersion());
    }
}
