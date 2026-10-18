package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where readiness is asked, and the fail-closed default in production (payment-adapter#293). */
class PurchaseIssuerConfigTest {

    // Unset outside prod: the staging compose name, so staging works with no new env var.
    @Test
    void unsetOutsideProdDefaultsToTheStagingComposeName() {
        assertEquals("http://purchase-issuer:8791", PurchaseConfiguration.issuerBaseUrl(new MockEnvironment()));
    }

    // Unset under a prod profile: empty, so every card checkout fails closed until it is named.
    @Test
    void unsetUnderProdIsEmptyAndFailsClosed() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        assertEquals("", PurchaseConfiguration.issuerBaseUrl(env));
    }

    // An explicit value always wins, prod or not.
    @Test
    void anExplicitUrlWins() {
        MockEnvironment env = new MockEnvironment().withProperty("purchase.issuer.base-url", " http://pi:1 ");
        env.setActiveProfiles("production");

        assertEquals("http://pi:1", PurchaseConfiguration.issuerBaseUrl(env));
    }
}
