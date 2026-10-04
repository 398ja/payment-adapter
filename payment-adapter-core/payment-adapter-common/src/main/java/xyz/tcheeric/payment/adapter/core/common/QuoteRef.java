package xyz.tcheeric.payment.adapter.core.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A log-safe handle for a quote id.
 *
 * <p>A paid mint quote that is not NUT-20 locked can be minted by anyone who knows its id, so a
 * quote id is a bearer claim on the customer's payment (cashu-mint#531). Logs are shipped and
 * kept, which puts every logged id in the hands of every log reader. A short hash still ties the
 * log lines of one quote together across services: the format ({@code q:} plus the first six
 * bytes of SHA-256, in hex) is the one imani-gateway-customer logs as {@code quote_ref}.
 */
public final class QuoteRef {

    private static final int REF_BYTES = 6;

    private QuoteRef() {
    }

    /** The correlation handle for {@code quoteId}, safe to log. */
    public static String of(String quoteId) {
        if (quoteId == null || quoteId.isEmpty()) {
            return "(none)";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(quoteId.getBytes(StandardCharsets.UTF_8));
            return "q:" + HexFormat.of().formatHex(digest, 0, REF_BYTES);
        } catch (NoSuchAlgorithmException e) {
            return "(hash-unavailable)";
        }
    }
}
