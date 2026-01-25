package xyz.tcheeric.payment.adapter.cash.nostr.codec;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URI encoder/decoder for NIP-XX Cash Payments.
 * Format: nostr+cash://pay?k=<M_e>&ref=<nonce>&amt=<int>&fiat=<code>&exp=<unix>&r=<relay>&enc=<mode>&v=0.2
 */
@Slf4j
@Getter
@Builder
public class NostrCashUri {

    public static final String SCHEME = "nostr+cash";
    public static final String HOST = "pay";
    public static final String PREFIX = SCHEME + "://" + HOST + "?";

    private static final Pattern URI_PATTERN = Pattern.compile("^nostr\\+cash://pay\\?(.+)$");
    private static final Pattern PARAM_PATTERN = Pattern.compile("([^&=]+)=([^&]*)");

    /**
     * Merchant's ephemeral public key (hex)
     */
    private final String merchantPubkey;

    /**
     * Invoice reference nonce
     */
    private final String ref;

    /**
     * Amount in minor units
     */
    private final Integer amount;

    /**
     * ISO 4217 currency code (null for satoshis)
     */
    private final String fiat;

    /**
     * Unix timestamp expiry
     */
    private final Long expiresAt;

    /**
     * Relay URLs
     */
    @Builder.Default
    private final List<String> relays = new ArrayList<>();

    /**
     * Encryption mode ("nip44")
     */
    @Builder.Default
    private final String encryptionMode = "nip44";

    /**
     * Gift wrap preference
     */
    @Builder.Default
    private final Boolean giftWrap = null;

    /**
     * Hashed location token (optional)
     */
    private final String locationHash;

    /**
     * Protocol version
     */
    @Builder.Default
    private final String version = "0.2";

    /**
     * Encode this URI to a string.
     */
    public String encode() {
        StringBuilder sb = new StringBuilder(PREFIX);

        // Required parameters
        sb.append("k=").append(urlEncode(merchantPubkey));
        sb.append("&ref=").append(urlEncode(ref));
        sb.append("&amt=").append(amount);

        // Fiat (if not satoshis)
        if (fiat != null && !fiat.isEmpty()) {
            sb.append("&fiat=").append(urlEncode(fiat));
        }

        // Expiry
        sb.append("&exp=").append(expiresAt);

        // Relays (multiple r= params)
        for (String relay : relays) {
            sb.append("&r=").append(urlEncode(relay));
        }

        // Encryption mode
        if (encryptionMode != null) {
            sb.append("&enc=").append(urlEncode(encryptionMode));
        }

        // Gift wrap preference
        if (giftWrap != null && giftWrap) {
            sb.append("&wrap=1");
        }

        // Location hash (optional)
        if (locationHash != null && !locationHash.isEmpty()) {
            sb.append("&h=").append(urlEncode(locationHash));
        }

        // Version
        if (version != null) {
            sb.append("&v=").append(urlEncode(version));
        }

        return sb.toString();
    }

    /**
     * Parse a NostrCashUri from a URI string.
     */
    public static NostrCashUri parse(String uri) {
        if (uri == null || uri.isEmpty()) {
            throw new IllegalArgumentException("URI cannot be null or empty");
        }

        Matcher uriMatcher = URI_PATTERN.matcher(uri);
        if (!uriMatcher.matches()) {
            throw new IllegalArgumentException("Invalid nostr+cash URI format: " + uri);
        }

        String queryString = uriMatcher.group(1);
        NostrCashUriBuilder builder = NostrCashUri.builder();
        List<String> relays = new ArrayList<>();

        Matcher paramMatcher = PARAM_PATTERN.matcher(queryString);
        while (paramMatcher.find()) {
            String key = paramMatcher.group(1);
            String value = urlDecode(paramMatcher.group(2));

            switch (key) {
                case "k":
                    builder.merchantPubkey(value);
                    break;
                case "ref":
                    builder.ref(value);
                    break;
                case "amt":
                    builder.amount(Integer.parseInt(value));
                    break;
                case "fiat":
                    builder.fiat(value);
                    break;
                case "exp":
                    builder.expiresAt(Long.parseLong(value));
                    break;
                case "r":
                    relays.add(value);
                    break;
                case "enc":
                    builder.encryptionMode(value);
                    break;
                case "wrap":
                    builder.giftWrap("1".equals(value));
                    break;
                case "h":
                    builder.locationHash(value);
                    break;
                case "v":
                    builder.version(value);
                    break;
                default:
                    log.debug("Unknown parameter in URI: {}", key);
            }
        }

        builder.relays(relays);
        NostrCashUri result = builder.build();

        // Validate required fields
        if (result.getMerchantPubkey() == null) {
            throw new IllegalArgumentException("Missing required parameter: k (merchant pubkey)");
        }
        if (result.getRef() == null) {
            throw new IllegalArgumentException("Missing required parameter: ref");
        }
        if (result.getAmount() == null) {
            throw new IllegalArgumentException("Missing required parameter: amt");
        }
        if (result.getExpiresAt() == null) {
            throw new IllegalArgumentException("Missing required parameter: exp");
        }
        if (result.getRelays().isEmpty()) {
            throw new IllegalArgumentException("Missing required parameter: r (at least one relay)");
        }

        return result;
    }

    /**
     * Check if this invoice has expired.
     */
    public boolean isExpired() {
        return Instant.now().getEpochSecond() > expiresAt;
    }

    /**
     * Get expiry as Instant.
     */
    public Instant getExpiresAtInstant() {
        return Instant.ofEpochSecond(expiresAt);
    }

    /**
     * URL-encode a value for URI parameter.
     */
    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * URL-decode a value from URI parameter.
     */
    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return encode();
    }
}
