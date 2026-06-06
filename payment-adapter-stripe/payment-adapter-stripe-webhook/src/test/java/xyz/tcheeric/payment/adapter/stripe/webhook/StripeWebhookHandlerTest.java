package xyz.tcheeric.payment.adapter.stripe.webhook;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
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
import xyz.tcheeric.payment.adapter.stripe.webhook.service.StripeWebhookSignatureVerifier;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeWebhookHandlerTest {

    private StripeWebhookHandler handler;

    @Mock
    private ProcessedStripeWebhookEventRepository processedEventRepository;

    @Mock
    private StripePaymentReferenceRepository paymentReferenceRepository;

    @Mock
    private QuoteClient quoteClient;

    @Mock
    private PaymentClient paymentClient;

    @Mock
    private StripeWebhookSignatureVerifier signatureVerifier;

    @Mock
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new StripeWebhookHandler(
                processedEventRepository,
                paymentReferenceRepository,
                quoteClient,
                paymentClient,
                signatureVerifier
        );
    }

    // Verifies a Checkout success webhook payload is parsed into the expected Stripe fields.
    @Test
    void parsesCheckoutSuccessPayload() throws Exception {
        String rawBody = """
                {
                  "id":"evt_123",
                  "type":"checkout.session.completed",
                  "created":1710000000,
                  "livemode":false,
                  "data":{"object":{
                    "id":"cs_test_123",
                    "payment_intent":"pi_test_123",
                    "amount_total":100,
                    "currency":"usd",
                    "payment_status":"paid",
                    "metadata":{"quote_id":"quote-123"}
                  }}
                }
                """;
        when(request.getInputStream()).thenReturn(toServletInputStream(rawBody));

        StripeWebhookPayload payload = handler.parsePayload(request);

        assertEquals("evt_123", payload.getEventId());
        assertEquals("checkout.session.completed", payload.getEventType());
        assertEquals("quote-123", payload.getQuoteId());
        assertEquals("cs_test_123", payload.getCheckoutSessionId());
        assertEquals("pi_test_123", payload.getPaymentIntentId());
        assertEquals(100, payload.getAmountTotal());
    }

    // Verifies duplicate processed Stripe events are rejected before any state mutation.
    @Test
    void rejectsPreviouslyProcessedEvents() {
        ProcessedStripeWebhookEvent processedEvent = new ProcessedStripeWebhookEvent();
        processedEvent.setEventId("evt_123");
        processedEvent.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSED);
        when(processedEventRepository.findById("evt_123")).thenReturn(Optional.of(processedEvent));

        StripeWebhookPayload payload = StripeWebhookPayload.builder()
                .eventId("evt_123")
                .eventType("checkout.session.completed")
                .eventTimestamp(Instant.now())
                .rawPayload("{}")
                .checkoutSessionId("cs_test_123")
                .build();

        assertThrows(WebhookDuplicateException.class, () -> handler.handle(payload));
        verify(paymentReferenceRepository, never()).save(any());
    }

    // Verifies a successful checkout event marks the quote and payment as paid and records the event as processed.
    @Test
    void handlesSuccessfulCheckout() throws Exception {
        GatewayQuote quote = GatewayQuote.create("quote-123", "cs_test_123", 1800, "Voucher", "https://checkout", 100, "usd");
        quote.setId(1L);
        GatewayPayment payment = GatewayPayment.create("https://checkout", null, "quote-123", "usd", 100, 0, 100, null, null);
        payment.setId(2L);

        StripePaymentReference reference = new StripePaymentReference();
        reference.setQuoteId("quote-123");
        reference.setCheckoutSessionId("cs_test_123");
        reference.setCreatedAt(Instant.now());
        reference.setUpdatedAt(Instant.now());

        when(processedEventRepository.findById("evt_123")).thenReturn(Optional.empty());
        when(processedEventRepository.save(any(ProcessedStripeWebhookEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentReferenceRepository.findByCheckoutSessionId("cs_test_123")).thenReturn(Optional.of(reference));
        when(paymentReferenceRepository.save(any(StripePaymentReference.class))).thenAnswer(invocation -> invocation.getArgument(0));
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
                .amountTotal(100)
                .currency("usd")
                .livemode(false)
                .paymentStatus("paid")
                .build();

        WebhookResult result = handler.handle(payload);

        assertEquals(State.PAID, result.newState());
        assertEquals("pi_test_123", result.paymentId());
        verify(quoteClient).updateQuote(any(GatewayQuote.class));
        verify(paymentClient).updatePayment(any(GatewayPayment.class));
    }

    // charge.refunded carries both `amount` (original charge) and `amount_refunded`
    // (the refunded total); the payload must capture the latter for partial refunds.
    @Test
    void parsesRefundedAmountFromChargeRefunded() throws Exception {
        String rawBody = """
                {
                  "id":"evt_refund",
                  "type":"charge.refunded",
                  "created":1710000000,
                  "livemode":false,
                  "data":{"object":{
                    "id":"ch_123",
                    "amount":1000,
                    "amount_refunded":400,
                    "currency":"usd",
                    "metadata":{"quote_id":"quote-123"}
                  }}
                }
                """;
        when(request.getInputStream()).thenReturn(toServletInputStream(rawBody));

        StripeWebhookPayload payload = handler.parsePayload(request);

        assertEquals("charge.refunded", payload.getEventType());
        assertEquals(1000, payload.getAmountTotal());
        assertEquals(400, payload.getRefundedAmountMinor());
    }

    // The persisted refund amount must be amount_refunded, not the original charge.
    @Test
    void recordsActualRefundedAmountNotOriginalCharge() throws Exception {
        StripePaymentReference reference = new StripePaymentReference();
        reference.setQuoteId("quote-123");
        reference.setChargeId("ch_123");
        reference.setCreatedAt(Instant.now());
        reference.setUpdatedAt(Instant.now());

        when(processedEventRepository.findById("evt_refund")).thenReturn(Optional.empty());
        when(processedEventRepository.save(any(ProcessedStripeWebhookEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentReferenceRepository.findByChargeId("ch_123")).thenReturn(Optional.of(reference));
        when(paymentReferenceRepository.save(any(StripePaymentReference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StripeWebhookPayload payload = StripeWebhookPayload.builder()
                .eventId("evt_refund")
                .eventType("charge.refunded")
                .eventTimestamp(Instant.now())
                .rawPayload("{\"id\":\"evt_refund\"}")
                .chargeId("ch_123")
                .amountTotal(1000)        // original charge
                .refundedAmountMinor(400) // partial refund
                .currency("usd")
                .build();

        handler.handle(payload);

        org.mockito.ArgumentCaptor<StripePaymentReference> captor =
                org.mockito.ArgumentCaptor.forClass(StripePaymentReference.class);
        verify(paymentReferenceRepository).save(captor.capture());
        assertEquals(400, captor.getValue().getRefundedAmountMinor());
    }

    private static ServletInputStream toServletInputStream(String content) {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override
            public int read() {
                return byteStream.read();
            }

            @Override
            public boolean isFinished() {
                return byteStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // no-op for tests
            }
        };
    }
}
