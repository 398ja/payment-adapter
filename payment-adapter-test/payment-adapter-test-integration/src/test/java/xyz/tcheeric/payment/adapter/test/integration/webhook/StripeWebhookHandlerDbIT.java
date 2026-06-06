package xyz.tcheeric.payment.adapter.test.integration.webhook;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ProcessedStripeWebhookEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePaymentReference;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeWebhookProcessingStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedStripeWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePaymentReferenceRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.StripeWebhookHandler;
import xyz.tcheeric.payment.adapter.stripe.webhook.StripeWebhookPayload;
import xyz.tcheeric.payment.adapter.stripe.webhook.service.StripeWebhookSignatureVerifier;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StripeWebhookHandlerDbIT extends BasePostgresIT {

    @Autowired
    private StripePaymentReferenceRepository stripePaymentReferenceRepository;

    @Autowired
    private ProcessedStripeWebhookEventRepository processedStripeWebhookEventRepository;

    private QuoteClient quoteClient;
    private PaymentClient paymentClient;
    private StripeWebhookHandler stripeWebhookHandler;

    @BeforeEach
    void cleanUp() {
        processedStripeWebhookEventRepository.deleteAll();
        stripePaymentReferenceRepository.deleteAll();

        quoteClient = mock(QuoteClient.class);
        paymentClient = mock(PaymentClient.class);
        StripeWebhookSignatureVerifier signatureVerifier = (rawPayload, signatureHeader) -> {
        };
        stripeWebhookHandler = new StripeWebhookHandler(
                processedStripeWebhookEventRepository,
                stripePaymentReferenceRepository,
                quoteClient,
                paymentClient,
                signatureVerifier
        );
    }

    // Verifies a successful Checkout event persists processed-event state and updates the Stripe reference.
    @Test
    void handle_checkoutSuccess_persistsProcessedEventAndReferenceUpdate() {
        StripePaymentReference reference = new StripePaymentReference();
        reference.setQuoteId("quote-123");
        reference.setCheckoutSessionId("cs_test_123");
        reference.setCreatedAt(Instant.now());
        reference.setUpdatedAt(Instant.now());
        stripePaymentReferenceRepository.save(reference);

        GatewayQuote quote = GatewayQuote.create("quote-123", "cs_test_123", 900, "Voucher", "https://checkout", 1500, "usd");
        GatewayPayment payment = GatewayPayment.create("https://checkout", null, "quote-123", "usd", 1500, 0, 1500, null, null);
        when(quoteClient.getByEntityId("quote-123")).thenReturn(quote);
        when(quoteClient.updateQuote(any(GatewayQuote.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentClient.getByQuoteId("quote-123")).thenReturn(payment);
        when(paymentClient.updatePayment(any(GatewayPayment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StripeWebhookPayload payload = StripeWebhookPayload.builder()
                .eventId("evt_123")
                .eventType("checkout.session.completed")
                .eventTimestamp(Instant.now())
                .rawPayload("{\"id\":\"evt_123\"}")
                .quoteId("quote-123")
                .checkoutSessionId("cs_test_123")
                .paymentIntentId("pi_test_123")
                .amountTotal(1500)
                .currency("usd")
                .paymentStatus("paid")
                .build();

        stripeWebhookHandler.handle(payload);

        ProcessedStripeWebhookEvent processedEvent = processedStripeWebhookEventRepository.findById("evt_123").orElseThrow();
        StripePaymentReference storedReference = stripePaymentReferenceRepository.findByQuoteId("quote-123").orElseThrow();

        assertThat(processedEvent.getProcessingStatus()).isEqualTo(StripeWebhookProcessingStatus.PROCESSED);
        assertThat(storedReference.getPaymentIntentId()).isEqualTo("pi_test_123");
        assertThat(storedReference.getStripeStatus()).isEqualTo("paid");
        assertThat(storedReference.getLastEventId()).isEqualTo("evt_123");
    }

    // Verifies duplicate processed Stripe events are rejected using the persisted event record.
    @Test
    void handle_duplicateProcessedEvent_throwsDuplicateException() {
        ProcessedStripeWebhookEvent processedEvent = new ProcessedStripeWebhookEvent();
        processedEvent.setEventId("evt_dup");
        processedEvent.setEventType("checkout.session.completed");
        processedEvent.setPayloadHash("abc");
        processedEvent.setLivemode(false);
        processedEvent.setReceivedAt(Instant.now());
        processedEvent.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSED);
        processedStripeWebhookEventRepository.save(processedEvent);

        StripeWebhookPayload payload = StripeWebhookPayload.builder()
                .eventId("evt_dup")
                .eventType("checkout.session.completed")
                .eventTimestamp(Instant.now())
                .rawPayload("{\"id\":\"evt_dup\"}")
                .checkoutSessionId("cs_dup")
                .build();

        assertThrows(WebhookDuplicateException.class, () -> stripeWebhookHandler.handle(payload));
    }

    // Verifies refund events update Stripe-native refund state without requiring quote or payment mutations.
    @Test
    void handle_refundEvent_updatesRefundMetadata() {
        StripePaymentReference reference = new StripePaymentReference();
        reference.setQuoteId("quote-refund");
        reference.setCheckoutSessionId("cs_refund");
        reference.setChargeId("ch_refund");
        reference.setCreatedAt(Instant.now());
        reference.setUpdatedAt(Instant.now());
        stripePaymentReferenceRepository.save(reference);

        StripeWebhookPayload payload = StripeWebhookPayload.builder()
                .eventId("evt_refund")
                .eventType("charge.refunded")
                .eventTimestamp(Instant.now())
                .rawPayload("{\"id\":\"evt_refund\"}")
                .chargeId("ch_refund")
                .amountTotal(900)
                .build();

        stripeWebhookHandler.handle(payload);

        StripePaymentReference storedReference = stripePaymentReferenceRepository.findByChargeId("ch_refund").orElseThrow();
        assertThat(storedReference.getStripeStatus()).isEqualTo("refunded");
        assertThat(storedReference.getRefundedAmountMinor()).isEqualTo(900);
    }

    // Verifies validation failures leave a failed event audit record instead of silently discarding the webhook.
    @Test
    void handle_checkoutAmountMismatch_marksEventAsFailed() {
        StripePaymentReference reference = new StripePaymentReference();
        reference.setQuoteId("quote-mismatch");
        reference.setCheckoutSessionId("cs_mismatch");
        reference.setCreatedAt(Instant.now());
        reference.setUpdatedAt(Instant.now());
        stripePaymentReferenceRepository.save(reference);

        GatewayQuote quote = GatewayQuote.create(
                "quote-mismatch",
                "cs_mismatch",
                900,
                "Voucher",
                "https://checkout",
                1500,
                "usd"
        );
        GatewayPayment payment = GatewayPayment.create(
                "https://checkout",
                null,
                "quote-mismatch",
                "usd",
                1500,
                0,
                1500,
                null,
                null
        );
        when(quoteClient.getByEntityId("quote-mismatch")).thenReturn(quote);
        when(paymentClient.getByQuoteId("quote-mismatch")).thenReturn(payment);

        StripeWebhookPayload payload = StripeWebhookPayload.builder()
                .eventId("evt_mismatch")
                .eventType("checkout.session.completed")
                .eventTimestamp(Instant.now())
                .rawPayload("{\"id\":\"evt_mismatch\"}")
                .quoteId("quote-mismatch")
                .checkoutSessionId("cs_mismatch")
                .paymentIntentId("pi_mismatch")
                .amountTotal(999)
                .currency("usd")
                .paymentStatus("paid")
                .build();

        assertThrows(WebhookProcessingException.class, () -> stripeWebhookHandler.handle(payload));

        ProcessedStripeWebhookEvent processedEvent = processedStripeWebhookEventRepository.findById("evt_mismatch")
                .orElseThrow();
        assertThat(processedEvent.getProcessingStatus()).isEqualTo(StripeWebhookProcessingStatus.FAILED);
        assertThat(processedEvent.getLastError()).contains("amount mismatch");
    }
}
