package xyz.tcheeric.payment.adapter.stripe.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ProcessedStripeWebhookEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeWebhookProcessingStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedStripeWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePaymentReferenceRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.service.StripeWebhookSignatureVerifier;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookSignatureException;

/** imani-deploy#210: every webhook path moves the counter it should, and only that one. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StripeWebhookMetricsTest {

    @Mock private ProcessedStripeWebhookEventRepository processed;
    @Mock private StripePaymentReferenceRepository references;
    @Mock private QuoteClient quotes;
    @Mock private PaymentClient payments;
    @Mock private StripeWebhookSignatureVerifier verifier;
    @Mock private StripePurchaseListener listener;
    @Mock private HttpServletRequest request;

    private SimpleMeterRegistry registry;
    private StripeWebhookHandler handler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        handler = new StripeWebhookHandler(processed, references, quotes, payments, verifier);
        handler.setMeterRegistry(registry);
        handler.setPurchaseListener(listener);
        when(processed.findById(any())).thenReturn(Optional.empty());
        when(processed.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private StripeWebhookPayload paid(String eventId) {
        return StripeWebhookPayload.builder()
                .eventId(eventId).eventType("checkout.session.completed")
                .rawPayload("{\"id\":\"" + eventId + "\"}")
                .checkoutSessionId("cs_1").paymentIntentId("pi_1")
                .amountTotal(2500).currency("gbp").paymentStatus("paid")
                .connectedAccountId("acct_1").metadata(Map.of()).build();
    }

    private double payments(String status, String reason) {
        return registry.find("imani.payments").tag("source", "card").tag("status", status).tag("reason", reason)
                .counters().stream().mapToDouble(Counter::count).sum();
    }

    private double webhooks(String outcome) {
        return registry.get("imani.payment.webhooks").tag("outcome", outcome).counter().count();
    }

    @Test
    void aPaidPurchaseCountsOneSuccessAndItsAmount() throws Exception {
        handler.handle(paid("evt_1"));

        assertEquals(1.0, payments("succeeded", "none"));
        assertEquals(2500.0, registry.get("imani.payment.amount.minor").tag("status", "succeeded")
                .tag("currency", "GBP").counter().count());
        assertEquals(1.0, webhooks("processed"));
    }

    @Test
    void aDuplicateWebhookIsCountedOnceAsAPayment() throws Exception {
        handler.handle(paid("evt_dup"));
        ProcessedStripeWebhookEvent done = new ProcessedStripeWebhookEvent();
        done.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSED);
        when(processed.findById("evt_dup")).thenReturn(Optional.of(done));

        assertThrows(WebhookDuplicateException.class, () -> handler.handle(paid("evt_dup")));

        assertEquals(1.0, payments("succeeded", "none"), "a redelivery is not a second sale");
        assertEquals(1.0, webhooks("duplicate"));
    }

    @Test
    void aBadSignatureIsCountedAsRejected() {
        doThrow(new WebhookSignatureException("bad")).when(verifier).verify(anyString(), any());

        assertThrows(WebhookSignatureException.class, () -> handler.validateSignature(paid("evt_sig"), request));

        assertEquals(1.0, webhooks("signature_rejected"));
        assertEquals(0.0, webhooks("verified"));
    }

    @Test
    void aGoodSignatureIsCountedAsVerified() {
        handler.validateSignature(paid("evt_ok"), request);
        assertEquals(1.0, webhooks("verified"));
    }

    /**
     * Review of #268: a failure that throws leaves the event FAILED, and Stripe retries it for up
     * to three days. Each retry must not be another failed payment.
     */
    @Test
    void aStripeRetryOfAFailedEventIsNotASecondFailure() throws Exception {
        handler.setPurchaseListener(null);
        assertThrows(WebhookProcessingException.class, () -> handler.handle(paid("evt_retry")));
        ProcessedStripeWebhookEvent failed = new ProcessedStripeWebhookEvent();
        failed.setEventId("evt_retry");
        failed.setProcessingStatus(StripeWebhookProcessingStatus.FAILED);
        when(processed.findById("evt_retry")).thenReturn(Optional.of(failed));

        assertThrows(WebhookProcessingException.class, () -> handler.handle(paid("evt_retry")));
        assertThrows(WebhookProcessingException.class, () -> handler.handle(paid("evt_retry")));

        assertEquals(1.0, payments("failed", "no_listener"), "a Stripe retry is not a second failed payment");
        assertEquals(3.0, webhooks("failed"), "each delivery is still a failed webhook");
    }

    @Test
    void aListenerRefusalIsAFailedWebhookAndNoSale() throws Exception {
        doThrow(new IllegalStateException("db down")).when(listener).onPurchasePaid(any());

        assertThrows(WebhookProcessingException.class, () -> handler.handle(paid("evt_fail")));

        assertEquals(1.0, webhooks("failed"));
        assertEquals(0.0, payments("succeeded", "none"));
    }

    @Test
    void aDeclinedAsyncPaymentIsAFailureWithAFixedReason() throws Exception {
        handler.handle(StripeWebhookPayload.builder()
                .eventId("evt_decl").eventType("checkout.session.async_payment_failed")
                .rawPayload("{}").currency("gbp").build());

        assertEquals(1.0, payments("failed", "card_declined"));
    }
}
