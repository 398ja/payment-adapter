package xyz.tcheeric.payment.adapter.test.integration.repository;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePaymentReference;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePaymentReferenceRepository;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;

import static org.assertj.core.api.Assertions.assertThat;

class StripePaymentReferenceRepositoryIT extends BasePostgresIT {

    @Autowired
    private StripePaymentReferenceRepository stripePaymentReferenceRepository;

    @BeforeEach
    void cleanUp() {
        stripePaymentReferenceRepository.deleteAll();
    }

    // Verifies a Stripe payment reference can be retrieved by the stored quote identifier.
    @Test
    void findByQuoteId_returnsSavedReference() {
        StripePaymentReference reference = new StripePaymentReference();
        reference.setQuoteId("quote-123");
        reference.setCheckoutSessionId("cs_test_123");
        reference.setPaymentIntentId("pi_test_123");
        reference.setChargeId("ch_test_123");
        reference.setStripeStatus("open");
        reference.setCreatedAt(Instant.now());
        reference.setUpdatedAt(Instant.now());
        stripePaymentReferenceRepository.save(reference);

        StripePaymentReference storedReference = stripePaymentReferenceRepository.findByQuoteId("quote-123").orElseThrow();

        assertThat(storedReference.getCheckoutSessionId()).isEqualTo("cs_test_123");
        assertThat(storedReference.getPaymentIntentId()).isEqualTo("pi_test_123");
    }

    // Verifies a Stripe payment reference can be resolved by the Checkout Session identifier.
    @Test
    void findByCheckoutSessionId_returnsSavedReference() {
        StripePaymentReference reference = new StripePaymentReference();
        reference.setQuoteId("quote-456");
        reference.setCheckoutSessionId("cs_test_456");
        reference.setStripeStatus("paid");
        reference.setCreatedAt(Instant.now());
        reference.setUpdatedAt(Instant.now());
        stripePaymentReferenceRepository.save(reference);

        StripePaymentReference storedReference = stripePaymentReferenceRepository.findByCheckoutSessionId("cs_test_456").orElseThrow();

        assertThat(storedReference.getQuoteId()).isEqualTo("quote-456");
    }

    // Verifies payment intent and charge identifiers can be resolved after webhook metadata is persisted.
    @Test
    void findByPaymentIntentIdAndChargeId_returnsSavedReference() {
        StripePaymentReference reference = new StripePaymentReference();
        reference.setQuoteId("quote-789");
        reference.setCheckoutSessionId("cs_test_789");
        reference.setPaymentIntentId("pi_test_789");
        reference.setChargeId("ch_test_789");
        reference.setRefundedAmountMinor(500);
        reference.setDisputed(true);
        reference.setCreatedAt(Instant.now());
        reference.setUpdatedAt(Instant.now());
        stripePaymentReferenceRepository.save(reference);

        StripePaymentReference byPaymentIntent = stripePaymentReferenceRepository.findByPaymentIntentId("pi_test_789")
                .orElseThrow();
        StripePaymentReference byCharge = stripePaymentReferenceRepository.findByChargeId("ch_test_789")
                .orElseThrow();

        assertThat(byPaymentIntent.getQuoteId()).isEqualTo("quote-789");
        assertThat(byCharge.getRefundedAmountMinor()).isEqualTo(500);
        assertThat(byCharge.isDisputed()).isTrue();
    }
}
