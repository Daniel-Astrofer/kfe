package source.kfe.application.settlement;

/**
 * Single binary flag outcome for forensic audit.
 *
 * @param flag   which settlement flag
 * @param pass   true = 1, false = 0
 * @param reason stable machine code (never user secrets)
 */
public record FlagEvaluation(
        SettlementFlag flag,
        boolean pass,
        String reason) {

    public static FlagEvaluation pass(SettlementFlag flag, String reason) {
        return new FlagEvaluation(flag, true, reason);
    }

    public static FlagEvaluation fail(SettlementFlag flag, String reason) {
        return new FlagEvaluation(flag, false, reason);
    }

    public int binary() {
        return pass ? 1 : 0;
    }
}
