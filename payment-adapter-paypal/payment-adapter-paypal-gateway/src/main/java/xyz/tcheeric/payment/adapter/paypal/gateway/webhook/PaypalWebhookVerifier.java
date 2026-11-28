package xyz.tcheeric.payment.adapter.paypal.gateway.webhook;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Signature;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import lombok.extern.slf4j.Slf4j;

/**
 * Verifies a PayPal webhook delivery locally, against PayPal's own certificate (ADR 0002), the
 * counterpart of {@code DefaultStripeWebhookSignatureVerifier}. No call to PayPal's
 * {@code verify-webhook-signature} endpoint, so a PayPal outage cannot turn every delivery into a
 * failure, and verification needs no OAuth token.
 *
 * <p>PayPal signs {@code <transmission id>|<transmission time>|<webhook id>|<crc32 of the raw body>}
 * with RSA-SHA256. The certificate's URL arrives in a header, so it is what an attacker would forge
 * first; it is trusted only when all of these hold:
 *
 * <ul>
 *   <li>the URL is {@code https} on a {@code paypal.com} host ({@link #certHostAllowed});</li>
 *   <li>the chain it serves validates (PKIX) to a CA in the JDK's trust store, and is in date;</li>
 *   <li>the leaf names a {@code paypal.com} host, so a certificate issued to anyone else is refused
 *       even when a public CA vouches for it.</li>
 * </ul>
 *
 * <p>The webhook id is ours and never taken from the request: a delivery signed for another
 * PayPal app, or another of our webhooks, does not verify here.
 */
@Slf4j
public class PaypalWebhookVerifier {

    public static final String TRANSMISSION_ID = "PAYPAL-TRANSMISSION-ID";
    public static final String TRANSMISSION_TIME = "PAYPAL-TRANSMISSION-TIME";
    public static final String TRANSMISSION_SIG = "PAYPAL-TRANSMISSION-SIG";
    public static final String CERT_URL = "PAYPAL-CERT-URL";
    public static final String AUTH_ALGO = "PAYPAL-AUTH-ALGO";

    /** RSA with SHA-2 only. PayPal sends SHA256withRSA; anything weaker is refused. */
    private static final Set<String> ALGORITHMS = Set.of("SHA256withRSA", "SHA384withRSA", "SHA512withRSA");

    static final int CERT_CACHE_LIMIT = 64;

    private final String webhookId;
    private final Predicate<URI> certHostAllowed;
    private final Function<URI, byte[]> certFetcher;
    private final ChainTrust chainTrust;
    private final Duration tolerance;
    private final Clock clock;
    private final Map<String, X509Certificate> verifiedLeaves = new ConcurrentHashMap<>();

    /** Production: PayPal hosts only, fetched over HTTPS, chains validated against the JDK trust store. */
    public PaypalWebhookVerifier(String webhookId, Predicate<String> trustedHost, Duration tolerance) {
        this(webhookId, uri -> isHttps(uri) && trustedHost.test(uri.getHost()), httpFetcher(),
                ChainTrust.jdkDefault(), tolerance, Clock.systemUTC());
    }

    public PaypalWebhookVerifier(String webhookId, Predicate<URI> certHostAllowed, Function<URI, byte[]> certFetcher,
            ChainTrust chainTrust, Duration tolerance, Clock clock) {
        this.webhookId = webhookId == null || webhookId.isBlank() ? null : webhookId.trim();
        this.certHostAllowed = certHostAllowed;
        this.certFetcher = certFetcher;
        this.chainTrust = chainTrust;
        this.tolerance = tolerance == null || tolerance.isZero() || tolerance.isNegative() ? null : tolerance;
        this.clock = clock;
    }

    public boolean isConfigured() {
        return webhookId != null;
    }

    /**
     * @param headers a case-insensitive lookup of the delivery's headers
     * @throws PaypalSignatureException when the delivery is not PayPal's, for our webhook
     */
    public void verify(byte[] rawBody, Function<String, String> headers) {
        if (webhookId == null) {
            throw new PaypalSignatureException("PayPal webhook id is not configured; every delivery is refused");
        }
        String id = required(headers, TRANSMISSION_ID);
        String time = required(headers, TRANSMISSION_TIME);
        String signature = required(headers, TRANSMISSION_SIG);
        String certUrl = required(headers, CERT_URL);
        String algorithm = headers.apply(AUTH_ALGO);
        algorithm = algorithm == null || algorithm.isBlank() ? "SHA256withRSA" : algorithm.trim();
        if (!ALGORITHMS.contains(algorithm)) {
            throw new PaypalSignatureException("PayPal webhook signed with an unaccepted algorithm: " + algorithm);
        }
        checkFresh(time);

        X509Certificate leaf = certificate(certUrl);
        CRC32 crc = new CRC32();
        crc.update(rawBody == null ? new byte[0] : rawBody);
        String message = id + "|" + time + "|" + webhookId + "|" + crc.getValue();
        try {
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(leaf.getPublicKey());
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(signature.trim()))) {
                throw new PaypalSignatureException("PayPal webhook signature does not match");
            }
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new PaypalSignatureException("PayPal webhook signature could not be checked: " + e.getMessage(), e);
        }
    }

    private void checkFresh(String time) {
        if (tolerance == null) {
            return;
        }
        Instant sent;
        try {
            sent = OffsetDateTime.parse(time.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new PaypalSignatureException("PayPal webhook transmission time is unreadable: " + time);
        }
        Instant now = clock.instant();
        if (sent.isBefore(now.minus(tolerance)) || sent.isAfter(now.plus(tolerance))) {
            throw new PaypalSignatureException("PayPal webhook transmission time " + time + " is outside "
                    + tolerance.toSeconds() + "s");
        }
    }

    private X509Certificate certificate(String certUrl) {
        X509Certificate cached = verifiedLeaves.get(certUrl);
        if (cached != null && inDate(cached)) {
            return cached;
        }
        URI uri;
        try {
            uri = URI.create(certUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new PaypalSignatureException("PayPal certificate URL is not a URL");
        }
        if (uri.getHost() == null || !certHostAllowed.test(uri)) {
            throw new PaypalSignatureException("PayPal certificate URL names an untrusted host: " + uri.getHost());
        }
        List<X509Certificate> chain = parse(certFetcher.apply(uri));
        if (chain.isEmpty()) {
            throw new PaypalSignatureException("PayPal certificate URL served no certificate");
        }
        X509Certificate leaf = chain.get(0);
        if (!inDate(leaf)) {
            throw new PaypalSignatureException("PayPal certificate is not in date");
        }
        if (!namesPaypal(leaf)) {
            throw new PaypalSignatureException("PayPal certificate is issued to "
                    + leaf.getSubjectX500Principal().getName() + ", not PayPal");
        }
        chainTrust.check(withIssuer(chain));
        if (verifiedLeaves.size() >= CERT_CACHE_LIMIT) {
            verifiedLeaves.clear();
        }
        verifiedLeaves.put(certUrl, leaf);
        return leaf;
    }

    /**
     * PayPal's sandbox serves the leaf alone, without the DigiCert intermediate PKIX needs. The leaf names its
     * issuer's certificate (Authority Information Access, caIssuers), which is fetched to complete the chain. The
     * fetched certificate earns no trust of its own: the completed chain must still validate to a JDK root.
     */
    private List<X509Certificate> withIssuer(List<X509Certificate> chain) {
        if (chain.size() > 1) {
            return chain;
        }
        return IssuerLocator.caIssuersUri(chain.get(0))
                .filter(IssuerLocator::isCertificateAuthorityHost)
                .map(uri -> {
                    List<X509Certificate> completed = new ArrayList<>(chain);
                    completed.addAll(parse(certFetcher.apply(uri)));
                    return completed;
                })
                .orElse(chain);
    }

    private boolean inDate(X509Certificate cert) {
        try {
            cert.checkValidity(java.util.Date.from(clock.instant()));
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** The leaf's CN, or a DNS subject alternative name, is a {@code paypal.com} host. */
    static boolean namesPaypal(X509Certificate cert) {
        Set<String> names = new HashSet<>();
        String dn = cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "CN=", 0, 3)) {
                names.add(trimmed.substring(3));
            }
        }
        try {
            if (cert.getSubjectAlternativeNames() != null) {
                for (List<?> san : cert.getSubjectAlternativeNames()) {
                    if (san.size() == 2 && Integer.valueOf(2).equals(san.get(0))) {
                        names.add(String.valueOf(san.get(1)));
                    }
                }
            }
        } catch (GeneralSecurityException ignored) {
            // CN alone decides
        }
        return names.stream().map(n -> n.toLowerCase(Locale.ROOT))
                .anyMatch(n -> n.equals("paypal.com") || n.endsWith(".paypal.com"));
    }

    private static List<X509Certificate> parse(byte[] pem) {
        if (pem == null || pem.length == 0) {
            return List.of();
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> out = new ArrayList<>();
            for (java.security.cert.Certificate cert : factory.generateCertificates(new ByteArrayInputStream(pem))) {
                out.add((X509Certificate) cert);
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new PaypalSignatureException("PayPal certificate could not be read: " + e.getMessage(), e);
        }
    }

    private static String required(Function<String, String> headers, String name) {
        String value = headers.apply(name);
        if (value == null || value.isBlank()) {
            throw new PaypalSignatureException("PayPal webhook is missing " + name);
        }
        return value;
    }

    private static boolean isHttps(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme());
    }

    private static Function<URI, byte[]> httpFetcher() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return uri -> {
            try {
                HttpResponse<byte[]> response = client.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new PaypalSignatureException("PayPal certificate URL answered " + response.statusCode());
                }
                return response.body();
            } catch (java.io.IOException e) {
                // Not the sender's fault: refused as unverifiable, PayPal redelivers.
                throw new PaypalSignatureException("PayPal certificate could not be fetched: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PaypalSignatureException("PayPal certificate fetch interrupted", e);
            }
        };
    }

    /** Whether a served chain is one a public CA vouches for. */
    @FunctionalInterface
    public interface ChainTrust {

        /** @throws PaypalSignatureException when the chain does not validate */
        void check(List<X509Certificate> chain);

        /** PKIX against the JDK's default trust store, revocation not checked (no OCSP dependency). */
        static ChainTrust jdkDefault() {
            Set<TrustAnchor> anchors = defaultAnchors();
            return chain -> {
                try {
                    CertificateFactory factory = CertificateFactory.getInstance("X.509");
                    PKIXParameters params = new PKIXParameters(anchors);
                    params.setRevocationEnabled(false);
                    CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(chain), params);
                } catch (GeneralSecurityException e) {
                    throw new PaypalSignatureException("PayPal certificate chain does not validate: "
                            + e.getMessage(), e);
                }
            };
        }

        /** Trust exactly these certificates as roots: for tests and a local mock, never production. */
        static ChainTrust anchoredAt(List<X509Certificate> roots) {
            Set<TrustAnchor> anchors = new HashSet<>();
            roots.forEach(root -> anchors.add(new TrustAnchor(root, null)));
            return chain -> {
                X509Certificate top = chain.get(chain.size() - 1);
                boolean anchored = anchors.stream().anyMatch(a -> a.getTrustedCert().equals(top));
                if (!anchored) {
                    try {
                        CertificateFactory factory = CertificateFactory.getInstance("X.509");
                        PKIXParameters params = new PKIXParameters(anchors);
                        params.setRevocationEnabled(false);
                        CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(chain), params);
                    } catch (GeneralSecurityException e) {
                        throw new PaypalSignatureException("PayPal certificate chain does not validate", e);
                    }
                }
            };
        }

        private static Set<TrustAnchor> defaultAnchors() {
            try {
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init((KeyStore) null);
                Set<TrustAnchor> anchors = new HashSet<>();
                for (javax.net.ssl.TrustManager manager : factory.getTrustManagers()) {
                    if (manager instanceof X509TrustManager x509) {
                        for (X509Certificate ca : x509.getAcceptedIssuers()) {
                            anchors.add(new TrustAnchor(ca, null));
                        }
                    }
                }
                return anchors;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("JDK trust store is unreadable", e);
            }
        }
    }
}
