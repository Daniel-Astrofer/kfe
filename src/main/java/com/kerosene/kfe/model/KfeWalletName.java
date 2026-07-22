package source.kfe.model;

import java.util.Arrays;
import java.text.Normalizer;

public enum KfeWalletName {
    INVESTMENT("Investimento"),
    DAILY("Dia a dia"),
    VEHICLE("Veiculo"),
    FUTURE_EXPENSES("Futuros gastos");

    private final String label;

    KfeWalletName(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static KfeWalletName fromLabel(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Wallet name is required.");
        }
        String normalized = normalize(value);
        return Arrays.stream(values())
                .filter(name -> name.name().equalsIgnoreCase(value.trim()) || normalize(name.label).equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Wallet name must be one of: Investimento, Dia a dia, Veiculo, Futuros gastos."));
    }

    private static String normalize(String value) {
        String ascii = Normalizer.normalize(value.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return ascii
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
