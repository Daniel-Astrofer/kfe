package source.kfe.service;

import org.springframework.stereotype.Service;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;

@Service
public class KfePricingService {

    private static final long KEROSENE_ONCHAIN_FEE_BPS = 90L;
    private static final long BPS_DENOMINATOR = 10_000L;

    public Quote quote(KfeRail rail, KfeDirection direction, long amountSats, long networkFeeSats) {
        if (amountSats <= 0) {
            throw new IllegalArgumentException("amountSats must be positive.");
        }
        if (networkFeeSats < 0) {
            throw new IllegalArgumentException("networkFeeSats must be non-negative.");
        }

        if (rail == KfeRail.INTERNAL || direction == KfeDirection.INTERNAL) {
            return new Quote(amountSats, amountSats, 0L, amountSats);
        }

        long keroseneFee = rail == KfeRail.ONCHAIN ? percentageFee(amountSats) : 0L;
        if (direction == KfeDirection.INBOUND) {
            long receiverAmount = amountSats - keroseneFee;
            if (receiverAmount <= 0) {
                throw new IllegalArgumentException("Inbound amount is too small after Kerosene fee.");
            }
            return new Quote(amountSats, receiverAmount, networkFeeSats, 0L, keroseneFee);
        }

        long totalDebit = Math.addExact(amountSats, Math.addExact(networkFeeSats, keroseneFee));
        return new Quote(amountSats, amountSats, networkFeeSats, totalDebit, keroseneFee);
    }

    private long percentageFee(long amountSats) {
        return Math.floorDiv(Math.addExact(Math.multiplyExact(amountSats, KEROSENE_ONCHAIN_FEE_BPS),
                BPS_DENOMINATOR - 1), BPS_DENOMINATOR);
    }

    public record Quote(
            long grossAmountSats,
            long receiverAmountSats,
            long networkFeeSats,
            long totalDebitSats,
            long keroseneFeeSats) {

        public Quote(long grossAmountSats, long receiverAmountSats, long networkFeeSats, long totalDebitSats) {
            this(grossAmountSats, receiverAmountSats, networkFeeSats, totalDebitSats, 0L);
        }
    }
}
