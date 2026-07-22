package source.kfe.rail;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies Lightning outbound destinations beyond pure BOLT11 invoices:
 * BOLT11, LNURL (bech32), Lightning Address (LUD-16), and keysend node pubkey.
 *
 * <p>Also unwraps common wallet wrappers the mobile app may send as-is:
 * {@code lightning:…}, BIP-21 {@code bitcoin:…?lightning=…}, whitespace/newlines.
 */
public final class LightningDestinationClassifier {

    public enum Kind {
        BOLT11,
        LNURL,
        LIGHTNING_ADDRESS,
        KEYSEND
    }

    public record Classified(Kind kind, String value) {
    }

    private static final Pattern LIGHTNING_ADDRESS =
            Pattern.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");
    private static final Pattern NODE_PUBKEY_HEX =
            Pattern.compile("^[0-9a-fA-F]{66}$");
    /** BOLT11 body after optional amount HRP — bech32 data. */
    private static final Pattern BOLT11 =
            Pattern.compile("(?i)^(lnbc|lntb|lnbcrt|lnsb|lntbs)[0-9a-z]+$");
    private static final Pattern LNURL_BECH32 =
            Pattern.compile("(?i)^lnurl1[0-9a-z]+$");
    private static final Pattern BIP21_LIGHTNING_PARAM =
            Pattern.compile("(?i)(?:^|[?&])lightning=([^&]+)");

    private LightningDestinationClassifier() {
    }

    public static Classified classify(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = normalize(raw);
        if (value.isEmpty()) {
            return null;
        }

        String lower = value.toLowerCase(Locale.ROOT);

        // BOLT11 invoice (mainnet / testnet / regtest / signet-style prefixes).
        if (BOLT11.matcher(lower).matches()) {
            return new Classified(Kind.BOLT11, lower);
        }
        // Prefix fallback when bech32 has rare chars / truncated samples still start as invoice.
        if (lower.startsWith("lnbc")
                || lower.startsWith("lntb")
                || lower.startsWith("lnbcrt")
                || lower.startsWith("lnsb")
                || lower.startsWith("lntbs")) {
            // Strip trailing non-bech32 junk sometimes appended by scanners.
            String cleaned = lower.replaceAll("[^0-9a-z].*$", "");
            if (BOLT11.matcher(cleaned).matches() || cleaned.length() > 20) {
                return new Classified(Kind.BOLT11, cleaned.isEmpty() ? lower : cleaned);
            }
        }

        // LNURL bech32 (LUD-01).
        if (LNURL_BECH32.matcher(lower).matches() || lower.startsWith("lnurl1")) {
            String cleaned = lower.replaceAll("[^0-9a-z].*$", "");
            return new Classified(Kind.LNURL, cleaned.isEmpty() ? lower : cleaned);
        }

        // Lightning Address (LUD-16): user@domain
        if (LIGHTNING_ADDRESS.matcher(value).matches()) {
            return new Classified(Kind.LIGHTNING_ADDRESS, value);
        }

        // Spontaneous payment (keysend) to node pubkey — compressed 33-byte hex.
        if (NODE_PUBKEY_HEX.matcher(value).matches()) {
            return new Classified(Kind.KEYSEND, value.toLowerCase(Locale.ROOT));
        }

        return null;
    }

    public static boolean isValidLightningOutboundReference(String raw) {
        return classify(raw) != null;
    }

    /**
     * Normalize wrappers the Flutter client may still forward as externalReference.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        // Collapse all whitespace / zero-width chars from QR/clipboard.
        String value = raw
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replaceAll("\\s+", "")
                .trim();
        if (value.isEmpty()) {
            return "";
        }

        // BIP-21: bitcoin:addr?amount=…&lightning=lnbc1…
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("bitcoin:") || lower.startsWith("web+bitcoin:")) {
            Matcher m = BIP21_LIGHTNING_PARAM.matcher(value);
            if (m.find()) {
                try {
                    value = java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    value = m.group(1);
                }
            }
        }

        // URI forms: lightning:LNURL1... / lightning:user@host / lightning://...
        if (value.regionMatches(true, 0, "lightning:", 0, "lightning:".length())) {
            value = value.substring("lightning:".length()).trim();
            if (value.startsWith("//")) {
                value = value.substring(2).trim();
            }
            // lightning:lnbc1...?amount= — keep path only
            int q = value.indexOf('?');
            if (q > 0) {
                value = value.substring(0, q);
            }
        }

        return value.trim();
    }
}
