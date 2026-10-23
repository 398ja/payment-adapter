package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A secp256k1 public key as Nostr and BIP-340 name it: the 32-byte x coordinate, 64 lower-case hex.
 *
 * <p>The same rule as imani-gateway-customer's F1 (gc#255): a 66-hex compressed key with an
 * {@code 02} or {@code 03} prefix names the same signing authority as its x coordinate, so both
 * parity variants compare equal to the bare x-only key. Only a 66-hex value is ever stripped: a
 * 64-hex key that happens to begin with {@code 02} is already x-only and stays as it is.
 */
final class XOnlyKey {

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    private XOnlyKey() {
    }

    /** The x-only form of {@code key}, or null when it is not a 64-hex or 02/03-prefixed 66-hex key. */
    static String of(String key) {
        if (key == null) {
            return null;
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        if (k.length() == 66 && (k.startsWith("02") || k.startsWith("03"))) {
            k = k.substring(2);
        }
        return HEX64.matcher(k).matches() ? k : null;
    }

    /** Whether two keys name the same x-only key. Never true when either is malformed. */
    static boolean same(String a, String b) {
        String x = of(a);
        return x != null && x.equals(of(b));
    }

    /** The first 12 hex, for logs: enough to tell keys apart, never a whole value. */
    static String prefix(String key) {
        String x = of(key);
        return x == null ? "(malformed)" : x.substring(0, 12);
    }
}
