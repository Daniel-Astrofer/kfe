package source.kfe.application.settlement;

/**
 * Thrown when the binary settlement gate product is 0.
 * Callers must not move balance after this exception.
 */
public class SettlementGateRejectedException extends IllegalStateException {

    private final SettlementGateResult result;

    public SettlementGateRejectedException(SettlementGateResult result) {
        super(messageFor(result));
        this.result = result;
    }

    public SettlementGateResult result() {
        return result;
    }

    private static String messageFor(SettlementGateResult result) {
        if (result == null || result.failedFlags().isEmpty()) {
            return "KFE settlement gate rejected the transaction.";
        }
        return "KFE settlement gate rejected the transaction. Failed flags: "
                + result.failedFlags().stream().map(Enum::name).toList();
    }
}
