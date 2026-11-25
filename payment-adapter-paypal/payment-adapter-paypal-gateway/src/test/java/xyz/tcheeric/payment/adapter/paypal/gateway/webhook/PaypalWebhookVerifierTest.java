package xyz.tcheeric.payment.adapter.paypal.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.CRC32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The local check of PayPal's webhook signature, against openssl-made fixtures: a key and its
 * self-signed certificate naming {@code messageverificationcerts.paypal.com}, and an impostor
 * certificate with the same name and a different key.
 */
class PaypalWebhookVerifierTest {

    static final String WEBHOOK_ID = "8PT597110X687430LKGECATA";
    static final String CERT_URL = "https://api.paypal.com/v1/notifications/certs/CERT-360caa42-fca2a594-1d93a270";
    // Inside the fixtures' validity (from 2026-11-23 for a century).
    static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");

    static byte[] certPem;
    static byte[] impostorPem;
    static PrivateKey key;
    static X509Certificate cert;

    final AtomicInteger fetches = new AtomicInteger();
    byte[] served;
    PaypalWebhookVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        certPem = resource("paypal/test-signing-cert.pem");
        impostorPem = resource("paypal/test-impostor-cert.pem");
        key = privateKey(new String(resource("paypal/test-signing-key.pem"), StandardCharsets.US_ASCII));
        cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(certPem));
        served = certPem;
        verifier = verifier(WEBHOOK_ID, Duration.ZERO);
    }

    PaypalWebhookVerifier verifier(String webhookId, Duration tolerance) {
        Function<URI, byte[]> fetcher = uri -> {
            fetches.incrementAndGet();
            return served;
        };
        return new PaypalWebhookVerifier(webhookId,
                uri -> "https".equals(uri.getScheme()) && uri.getHost().endsWith(".paypal.com"), fetcher,
                PaypalWebhookVerifier.ChainTrust.anchoredAt(List.of(cert)), tolerance, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aDeliveryPaypalSignedForOurWebhookVerifies() throws Exception {
        byte[] body = "{\"id\":\"WH-1\",\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\"}".getBytes(StandardCharsets.UTF_8);
        assertThatCode(() -> verifier.verify(body, headers(sign(body, "tx-1", "2027-01-01T00:00:00Z", WEBHOOK_ID))))
                .doesNotThrowAnyException();
    }

    @Test
    void theCertificateIsFetchedOnceAndCached() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        verifier.verify(body, headers(sign(body, "tx-1", "2027-01-01T00:00:00Z", WEBHOOK_ID)));
        verifier.verify(body, headers(sign(body, "tx-2", "2027-01-01T00:00:01Z", WEBHOOK_ID)));
        assertThat(fetches).hasValue(1);
    }

    @Test
    void aTamperedBodyIsRefused() throws Exception {
        byte[] body = "{\"amount\":\"10.00\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = sign(body, "tx-1", "2027-01-01T00:00:00Z", WEBHOOK_ID);
        byte[] tampered = "{\"amount\":\"99.00\"}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifier.verify(tampered, headers(headers)))
                .isInstanceOf(PaypalSignatureException.class).hasMessageContaining("does not match");
    }

    @Test
    void aDeliverySignedForAnotherWebhookIsRefused() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = sign(body, "tx-1", "2027-01-01T00:00:00Z", "SOME-OTHER-WEBHOOK");
        assertThatThrownBy(() -> verifier.verify(body, headers(headers))).isInstanceOf(PaypalSignatureException.class);
    }

    @Test
    void aCertificateFromAnUntrustedHostIsNeverFetched() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = sign(body, "tx-1", "2027-01-01T00:00:00Z", WEBHOOK_ID);
        headers.put(PaypalWebhookVerifier.CERT_URL, "https://evil.example/cert.pem");
        assertThatThrownBy(() -> verifier.verify(body, headers(headers)))
                .isInstanceOf(PaypalSignatureException.class).hasMessageContaining("untrusted host");
        headers.put(PaypalWebhookVerifier.CERT_URL, "http://api.paypal.com/cert.pem");
        assertThatThrownBy(() -> verifier.verify(body, headers(headers))).isInstanceOf(PaypalSignatureException.class);
        assertThat(fetches).hasValue(0);
    }

    @Test
    void aCertificateNoTrustedRootVouchesForIsRefused() throws Exception {
        // Same name, different key, not one of the anchors.
        served = impostorPem;
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifier.verify(body, headers(sign(body, "tx-1", "2027-01-01T00:00:00Z", WEBHOOK_ID))))
                .isInstanceOf(PaypalSignatureException.class).hasMessageContaining("chain");
    }

    @Test
    void missingHeadersAndAWeakAlgorithmAreRefused() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = sign(body, "tx-1", "2027-01-01T00:00:00Z", WEBHOOK_ID);
        Map<String, String> noSig = new HashMap<>(headers);
        noSig.remove(PaypalWebhookVerifier.TRANSMISSION_SIG);
        assertThatThrownBy(() -> verifier.verify(body, headers(noSig))).hasMessageContaining("missing");
        headers.put(PaypalWebhookVerifier.AUTH_ALGO, "SHA1withRSA");
        assertThatThrownBy(() -> verifier.verify(body, headers(headers))).hasMessageContaining("algorithm");
    }

    @Test
    void noWebhookIdRefusesEverything() throws Exception {
        PaypalWebhookVerifier unconfigured = verifier("  ", Duration.ZERO);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.verify(body, headers(sign(body, "tx", "2027-01-01T00:00:00Z", ""))))
                .hasMessageContaining("not configured");
    }

    @Test
    void aToleranceRefusesOldDeliveriesOnlyWhenSet() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> old = sign(body, "tx-1", "2026-12-31T00:00:00Z", WEBHOOK_ID);
        assertThatCode(() -> verifier.verify(body, headers(old))).doesNotThrowAnyException();
        PaypalWebhookVerifier strict = verifier(WEBHOOK_ID, Duration.ofMinutes(5));
        assertThatThrownBy(() -> strict.verify(body, headers(old))).hasMessageContaining("outside");
    }

    @Test
    void onlyPaypalNamedCertificatesAreAccepted() {
        assertThat(PaypalWebhookVerifier.namesPaypal(cert)).isTrue();
    }

    private static Map<String, String> sign(byte[] body, String transmissionId, String time, String webhookId)
            throws Exception {
        CRC32 crc = new CRC32();
        crc.update(body);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update((transmissionId + "|" + time + "|" + webhookId + "|" + crc.getValue())
                .getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = new HashMap<>();
        headers.put(PaypalWebhookVerifier.TRANSMISSION_ID, transmissionId);
        headers.put(PaypalWebhookVerifier.TRANSMISSION_TIME, time);
        headers.put(PaypalWebhookVerifier.TRANSMISSION_SIG, Base64.getEncoder().encodeToString(signer.sign()));
        headers.put(PaypalWebhookVerifier.CERT_URL, CERT_URL);
        headers.put(PaypalWebhookVerifier.AUTH_ALGO, "SHA256withRSA");
        return headers;
    }

    private static Function<String, String> headers(Map<String, String> headers) {
        Map<String, String> insensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        insensitive.putAll(headers);
        return insensitive::get;
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream in = PaypalWebhookVerifierTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing test resource " + name);
            }
            return in.readAllBytes();
        }
    }

    static PrivateKey privateKey(String pem) throws Exception {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }
}
