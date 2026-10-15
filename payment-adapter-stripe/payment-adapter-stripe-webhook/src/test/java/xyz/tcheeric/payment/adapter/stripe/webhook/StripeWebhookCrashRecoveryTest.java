package xyz.tcheeric.payment.adapter.stripe.webhook;

import java.time.Instant;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card atomicity audit, scenario S5b (2026-10-15): a crash between the PROCESSING mark and the
 * purchase row must not turn Stripe's retry into a "duplicate".
 *
 * <p>Before: any PROCESSING row answered "duplicate" (200), so a process that died mid-event made
 * Stripe stop retrying a PAID purchase that was never recorded. Reproduced on a local stack
 * (harness S5b: redelivery 200 "already processing", 0 rows).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StripeWebhookCrashRecoveryTest {

    private static final String ACCOUNT = "acct_1MerchantXyz";

    @Mock private ProcessedStripeWebhookEventRepository processedEventRepository;
    @Mock private StripePaymentReferenceRepository paymentReferenceRepository;
    @Mock private QuoteClient quoteClient;
    @Mock private PaymentClient paymentClient;
    @Mock private StripeWebhookSignatureVerifier signatureVerifier;
    @Mock private StripePurchaseListener purchaseListener;

    private StripeWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StripeWebhookHandler(processedEventRepository, paymentReferenceRepository,
                quoteClient, paymentClient, signatureVerifier);
        handler.setPurchaseListener(purchaseListener);
        when(processedEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void aProcessingMarkLeftByACrashIsTakenOverAndThePurchaseRecorded() throws Exception {
        leftBehind(StripeWebhookProcessingStatus.PROCESSING, Instant.now().minusSeconds(600));

        handler.handle(paidPurchase());

        verify(purchaseListener).onPurchasePaid(any());
    }

    @Test
    void aMarkFromBeforeTheColumnExistedIsReadAsStale() throws Exception {
        leftBehind(StripeWebhookProcessingStatus.PROCESSING, null);

        handler.handle(paidPurchase());

        verify(purchaseListener).onPurchasePaid(any());
    }

    @Test
    void aFreshMarkIsNotAcknowledgedAsDoneSoStripeRetries() throws Exception {
        // Another replica may be working it right now. Not "duplicate" (a 200 ends Stripe's
        // retries); a processing error, which answers 5xx and brings the event back later.
        leftBehind(StripeWebhookProcessingStatus.PROCESSING, Instant.now().minusSeconds(5));

        assertThrows(WebhookProcessingException.class, () -> handler.handle(paidPurchase()));
        verify(purchaseListener, never()).onPurchasePaid(any());
    }

    @Test
    void aProcessedEventIsStillADuplicate() throws Exception {
        leftBehind(StripeWebhookProcessingStatus.PROCESSED, Instant.now().minusSeconds(600));

        assertThrows(WebhookDuplicateException.class, () -> handler.handle(paidPurchase()));
        verify(purchaseListener, never()).onPurchasePaid(any());
    }

    @Test
    void theMarkRecordsWhenItWasTaken() throws Exception {
        when(processedEventRepository.findById("evt_crash")).thenReturn(Optional.empty());
        org.mockito.ArgumentCaptor<ProcessedStripeWebhookEvent> saved =
                org.mockito.ArgumentCaptor.forClass(ProcessedStripeWebhookEvent.class);

        handler.handle(paidPurchase());

        verify(processedEventRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertNotNull(saved.getAllValues().get(0).getProcessingStartedAt());
        assertEquals(StripeWebhookProcessingStatus.PROCESSED, saved.getValue().getProcessingStatus());
    }

    private void leftBehind(StripeWebhookProcessingStatus status, Instant startedAt) {
        ProcessedStripeWebhookEvent prior = new ProcessedStripeWebhookEvent();
        prior.setEventId("evt_crash");
        prior.setEventType("checkout.session.completed");
        prior.setPayloadHash("x");
        prior.setReceivedAt(Instant.now().minusSeconds(900));
        prior.setProcessingStatus(status);
        prior.setProcessingStartedAt(startedAt);
        when(processedEventRepository.findById("evt_crash")).thenReturn(Optional.of(prior));
    }

    private StripeWebhookPayload paidPurchase() {
        return StripeWebhookPayload.builder()
                .eventId("evt_crash")
                .eventType("checkout.session.completed")
                .eventTimestamp(Instant.now())
                .rawPayload("{\"id\":\"evt_crash\"}")
                .checkoutSessionId("cs_crash")
                .paymentIntentId("pi_crash")
                .amountTotal(500)
                .currency("gbp")
                .paymentStatus("paid")
                .connectedAccountId(ACCOUNT)
                .metadata(Map.of("buyer_pubkey", "a".repeat(64)))
                .build();
    }
}
