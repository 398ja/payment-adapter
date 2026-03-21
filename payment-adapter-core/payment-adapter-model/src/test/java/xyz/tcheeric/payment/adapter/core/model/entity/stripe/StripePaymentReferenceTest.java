package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StripePaymentReferenceTest {

    // Verifies Stripe payment references retain the core Stripe correlation fields.
    @Test
    void keepsStripeCorrelationFields() {
        Instant now = Instant.now();
        StripePaymentReference reference = new StripePaymentReference();
        reference.setQuoteId("quote-123");
        reference.setCheckoutSessionId("cs_test_123");
        reference.setPaymentIntentId("pi_test_123");
        reference.setChargeId("ch_test_123");
        reference.setStripeStatus("paid");
        reference.setLivemode(false);
        reference.setCreatedAt(now);
        reference.setUpdatedAt(now);

        assertThat(reference.getQuoteId()).isEqualTo("quote-123");
        assertThat(reference.getCheckoutSessionId()).isEqualTo("cs_test_123");
        assertThat(reference.getPaymentIntentId()).isEqualTo("pi_test_123");
        assertThat(reference.getChargeId()).isEqualTo("ch_test_123");
        assertThat(reference.isLivemode()).isFalse();
    }
}
