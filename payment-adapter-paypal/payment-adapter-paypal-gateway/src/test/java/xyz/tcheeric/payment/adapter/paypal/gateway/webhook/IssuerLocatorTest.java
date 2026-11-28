package xyz.tcheeric.payment.adapter.paypal.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;

/** Regression: PayPal's sandbox serves a leaf without its intermediate, so the issuer must be locatable. */
class IssuerLocatorTest {

    // The real sandbox webhook certificate names DigiCert's intermediate as its issuer.
    @Test
    void theSandboxLeafNamesItsDigicertIssuer() throws Exception {
        X509Certificate leaf = sandboxLeaf();

        assertThat(IssuerLocator.caIssuersUri(leaf))
                .contains(URI.create("http://cacerts.digicert.com/DigiCertEVRSACAG2.crt"));
    }

    // Only a known public CA's host is fetched from, never a host an attacker's certificate could name.
    @Test
    void onlyCertificateAuthorityHostsAreFetched() {
        assertThat(IssuerLocator.isCertificateAuthorityHost(URI.create("http://cacerts.digicert.com/x.crt"))).isTrue();
        assertThat(IssuerLocator.isCertificateAuthorityHost(URI.create("http://evil.example/x.crt"))).isFalse();
        assertThat(IssuerLocator.isCertificateAuthorityHost(URI.create("http://digicert.com.evil.example/x"))).isFalse();
        assertThat(IssuerLocator.isCertificateAuthorityHost(URI.create("file:///etc/passwd"))).isFalse();
    }

    private static X509Certificate sandboxLeaf() throws Exception {
        try (InputStream pem = IssuerLocatorTest.class.getResourceAsStream("/paypal/sandbox-webhook-leaf.pem")) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
        }
    }
}
