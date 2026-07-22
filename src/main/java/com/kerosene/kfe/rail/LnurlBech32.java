package source.kfe.rail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal Bech32 decoder for LNURL (LUD-01): HRP {@code lnurl}, data is the URL bytes.
 */
public final class LnurlBech32 {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    private LnurlBech32() {
    }

    /**
     * Decode {@code lnurl1...} to the HTTPS/HTTP URL, or null if invalid.
     */
    public static String decodeToUrl(String lnurl) {
        if (lnurl == null || lnurl.isBlank()) {
            return null;
        }
        String lower = lnurl.trim().toLowerCase(Locale.ROOT);
        int sep = lower.lastIndexOf('1');
        if (sep < 1 || sep + 7 > lower.length()) {
            return null;
        }
        String hrp = lower.substring(0, sep);
        if (!"lnurl".equals(hrp)) {
            return null;
        }
        String dataPart = lower.substring(sep + 1);
        int[] data = new int[dataPart.length()];
        for (int i = 0; i < dataPart.length(); i++) {
            int v = CHARSET.indexOf(dataPart.charAt(i));
            if (v < 0) {
                return null;
            }
            data[i] = v;
        }
        if (!verifyChecksum(hrp, data)) {
            return null;
        }
        // strip 6 checksum values
        int[] payload = new int[data.length - 6];
        System.arraycopy(data, 0, payload, 0, payload.length);
        byte[] bytes = convertBits(payload, 5, 8, false);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String url = new String(bytes, StandardCharsets.UTF_8).trim();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return null;
        }
        return url;
    }

    private static boolean verifyChecksum(String hrp, int[] data) {
        int[] values = new int[hrpExpand(hrp).length + data.length];
        System.arraycopy(hrpExpand(hrp), 0, values, 0, hrpExpand(hrp).length);
        System.arraycopy(data, 0, values, hrpExpand(hrp).length, data.length);
        return polymod(values) == 1;
    }

    private static int[] hrpExpand(String hrp) {
        int[] ret = new int[hrp.length() * 2 + 1];
        for (int i = 0; i < hrp.length(); i++) {
            ret[i] = hrp.charAt(i) >> 5;
        }
        ret[hrp.length()] = 0;
        for (int i = 0; i < hrp.length(); i++) {
            ret[hrp.length() + 1 + i] = hrp.charAt(i) & 31;
        }
        return ret;
    }

    private static int polymod(int[] values) {
        int chk = 1;
        int[] generators = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        for (int v : values) {
            int b = chk >> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ v;
            for (int i = 0; i < 5; i++) {
                if (((b >> i) & 1) != 0) {
                    chk ^= generators[i];
                }
            }
        }
        return chk;
    }

    private static byte[] convertBits(int[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        List<Integer> out = new ArrayList<>();
        int maxv = (1 << toBits) - 1;
        for (int value : data) {
            if (value < 0 || (value >> fromBits) != 0) {
                return null;
            }
            acc = (acc << fromBits) | value;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                out.add((acc >> bits) & maxv);
            }
        }
        if (pad) {
            if (bits > 0) {
                out.add((acc << (toBits - bits)) & maxv);
            }
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
            // leftover bits must be zero when not padding (strict)
            if (bits >= fromBits) {
                return null;
            }
            // allow trailing zeros only
        }
        byte[] result = new byte[out.size()];
        for (int i = 0; i < out.size(); i++) {
            result[i] = out.get(i).byteValue();
        }
        return result;
    }
}
