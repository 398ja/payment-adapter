package xyz.tcheeric.payment.adapter.paypal.gateway.webhook;

import java.net.URI;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Reads where a certificate's issuer can be fetched from (RFC 5280 Authority Information Access, caIssuers). */
final class IssuerLocator {

    private static final String AUTHORITY_INFO_ACCESS = "1.3.6.1.5.5.7.1.1";
    /** DER of OID 1.3.6.1.5.5.7.48.2 (id-ad-caIssuers). */
    private static final byte[] CA_ISSUERS = {0x06, 0x08, 0x2b, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02};
    private static final int URI_TAG = 0x86;
    /** Issuers are fetched only from the public CAs PayPal's webhook certificates chain to. */
    private static final Set<String> CA_DOMAINS = Set.of("digicert.com");

    private IssuerLocator() {
    }

    static Optional<URI> caIssuersUri(X509Certificate cert) {
        byte[] extension = cert.getExtensionValue(AUTHORITY_INFO_ACCESS);
        if (extension == null) {
            return Optional.empty();
        }
        for (int at = 0; at + CA_ISSUERS.length + 2 <= extension.length; at++) {
            if (Arrays.equals(extension, at, at + CA_ISSUERS.length, CA_ISSUERS, 0, CA_ISSUERS.length)) {
                return uriAt(extension, at + CA_ISSUERS.length);
            }
        }
        return Optional.empty();
    }

    static boolean isCertificateAuthorityHost(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean web = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        return web && CA_DOMAINS.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    private static Optional<URI> uriAt(byte[] der, int at) {
        if (at + 2 > der.length || (der[at] & 0xff) != URI_TAG) {
            return Optional.empty();
        }
        int length = der[at + 1] & 0xff;
        if (length > 0x7f || at + 2 + length > der.length) {
            return Optional.empty();
        }
        try {
            return Optional.of(URI.create(new String(der, at + 2, length, StandardCharsets.US_ASCII)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
