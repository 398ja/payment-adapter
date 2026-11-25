package xyz.tcheeric.payment.adapter.paypal.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.PaypalCheckout;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ProcessedPaypalWebhookEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeWebhookProcessingStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.PaypalCheckoutRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedPaypalWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.webhook.PaypalSignatureException;
import xyz.tcheeric.payment.adapter.paypal.gateway.webhook.PaypalWebhookVerifier;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalCaptureService.Outcome;
import xyz.tcheeric.payment.adapter.paypal.webhook.PaypalCaptureService.PaypalCaptureException;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.ReversedPurchase;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookSignatureException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

class PaypalWebhookHandlerTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String ORDER = "5O190127TN364715T";
    static final String CAPTURE = "3C679366HH908993F";

    PaypalWebhookVerifier verifier;
    ProcessedPaypalWebhookEventRepository events;
    PaypalCheckoutRepository checkouts;
    StripePurchaseRepository purchases;
    PaypalCaptureService captures;
    StripePurchaseListener listener;
    PaypalWebhookHandler handler;
    Map<String, ProcessedPaypalWebhookEvent> store = new HashMap<>();
    PaypalCheckout checkout;

    @BeforeEach
    void setUp() {
        verifier = mock(PaypalWebhookVerifier.class);
        events = mock(ProcessedPaypalWebhookEventRepository.class);
        checkouts = mock(PaypalCheckoutRepository.class);
        purchases = mock(StripePurchaseRepository.class);
        captures = mock(PaypalCaptureService.class);
        listener = mock(StripePurchaseListener.class);
        handler = new PaypalWebhookHandler(verifier, events, checkouts, purchases, captures, listener, "Test", false);
        lenient().when(events.save(any())).thenAnswer(inv -> {
            ProcessedPaypalWebhookEvent e = inv.getArgument(0);
            store.put(e.getEventId(), e);
            return e;
        });
        lenient().when(events.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        checkout = PaypalCaptureServiceTest.checkout();
        lenient().when(checkouts.findByOrderId(ORDER)).thenReturn(Optional.of(checkout));
    }

    @Test
    void anApprovedOrderIsCapturedAtOnce() throws Exception {
        when(captures.capture(checkout, false)).thenReturn(Outcome.RECORDED);

        WebhookResult result = handler.handle(payload("WH-1", "CHECKOUT.ORDER.APPROVED", "{\"id\":\"" + ORDER + "\"}"));

        assertThat(result.metadata()).containsEntry("outcome", "RECORDED");
        assertThat(store.get("WH-1").getProcessingStatus()).isEqualTo(StripeWebhookProcessingStatus.PROCESSED);
    }

    @Test
    void anOutageWhileCapturingAsksPaypalToComeBack() {
        when(captures.capture(checkout, false)).thenReturn(Outcome.UNAVAILABLE);

        assertThatThrownBy(() -> handler.handle(payload("WH-1", "CHECKOUT.ORDER.APPROVED", "{\"id\":\"" + ORDER + "\"}")))
                .isInstanceOf(WebhookProcessingException.class);
        assertThat(store.get("WH-1").getProcessingStatus()).isEqualTo(StripeWebhookProcessingStatus.FAILED);
    }

    @Test
    void anOrderNotCreatedHereIsAcknowledged() throws Exception {
        WebhookResult result = handler.handle(payload("WH-1", "CHECKOUT.ORDER.APPROVED", "{\"id\":\"OTHER\"}"));

        assertThat(result.metadata()).containsEntry("ignored", true);
        verify(captures, never()).capture(any(), anyBoolean());
    }

    @Test
    void aCompletedCaptureOfOursIsSettled() throws Exception {
        when(captures.settleCapture(eq(checkout), any(), eq(false))).thenReturn(Outcome.RECORDED);

        WebhookResult result = handler.handle(payload("WH-2", "PAYMENT.CAPTURE.COMPLETED", capture("test:q-1")));

        assertThat(result.metadata()).containsEntry("reference", CAPTURE);
    }

    @Test
    void aCaptureNamingAnotherDeploymentIsNotRecorded() throws Exception {
        when(checkouts.findByOrderId(ORDER)).thenReturn(Optional.empty());
        when(checkouts.findByCaptureId(CAPTURE)).thenReturn(Optional.empty());

        WebhookResult result = handler.handle(payload("WH-3", "PAYMENT.CAPTURE.COMPLETED", capture("prod:q-1")));

        assertThat(result.metadata()).containsEntry("ignored", true);
        verify(captures, never()).recordOrphan(any(), anyBoolean());
    }

    @Test
    void anOrphanCaptureOfOurSellerIsRecordedForAPerson() throws Exception {
        when(checkouts.findByOrderId(ORDER)).thenReturn(Optional.empty());
        when(checkouts.findByCaptureId(CAPTURE)).thenReturn(Optional.empty());
        when(captures.recordOrphan(any(), eq(false))).thenReturn(true);

        WebhookResult result = handler.handle(payload("WH-4", "PAYMENT.CAPTURE.COMPLETED", capture(null)));

        assertThat(result.metadata()).containsEntry("outcome", "RECORDED");
    }

    @Test
    void aListenerFailureIsRetriedNotSwallowed() {
        when(captures.settleCapture(any(), any(), anyBoolean()))
                .thenThrow(new PaypalCaptureException("db", new IllegalStateException()));

        assertThatThrownBy(() -> handler.handle(payload("WH-5", "PAYMENT.CAPTURE.COMPLETED", capture("test:q-1"))))
                .isInstanceOf(WebhookProcessingException.class);
        assertThat(store.get("WH-5").getProcessingStatus()).isEqualTo(StripeWebhookProcessingStatus.FAILED);
    }

    @Test
    void aRefundFindsItsCaptureThroughTheUpLinkAndCarriesTheRunningTotal() throws Exception {
        when(purchases.findByPaymentIntentId(CAPTURE)).thenReturn(Optional.of(row()));

        handler.handle(payload("WH-6", "PAYMENT.CAPTURE.REFUNDED", """
                {"id":"1JU08902781691411","status":"COMPLETED","amount":{"currency_code":"USD","value":"2.50"},
                 "seller_payable_breakdown":{"total_refunded_amount":{"currency_code":"USD","value":"5.00"}},
                 "links":[{"rel":"up","href":"https://api.paypal.com/v2/payments/captures/%s"}]}""".formatted(CAPTURE)));

        ReversedPurchase reversal = reversal();
        assertThat(reversal.paymentIntentId()).isEqualTo(CAPTURE);
        assertThat(reversal.refundedAmountMinor()).isEqualTo(500L);
        assertThat(reversal.amountMinor()).isEqualTo(1250L);
        assertThat(reversal.connectedAccountId()).isEqualTo("SELLER123");
        assertThat(reversal.disputed()).isFalse();
    }

    @Test
    void aReversalTakesBackTheWholeCapture() throws Exception {
        when(purchases.findByPaymentIntentId(CAPTURE)).thenReturn(Optional.of(row()));

        handler.handle(payload("WH-7", "PAYMENT.CAPTURE.REVERSED", """
                {"id":"R1","amount":{"currency_code":"USD","value":"1.00"},
                 "links":[{"rel":"up","href":"https://api.paypal.com/v2/payments/captures/%s"}]}""".formatted(CAPTURE)));

        assertThat(reversal().refundedAmountMinor()).isEqualTo(1250L);
    }

    @Test
    void aDisputeBlocksEachDisputedCapture() throws Exception {
        handler.handle(payload("WH-8", "CUSTOMER.DISPUTE.CREATED", dispute(null)));

        ReversedPurchase reversal = reversal();
        assertThat(reversal.disputed()).isTrue();
        assertThat(reversal.paymentIntentId()).isEqualTo(CAPTURE);
        assertThat(reversal.chargeId()).isEqualTo("PP-D-1");
        assertThat(reversal.amountMinor()).isEqualTo(1250L);
    }

    @Test
    void aDisputeResolvedForTheSellerIsWon() throws Exception {
        handler.handle(payload("WH-9", "CUSTOMER.DISPUTE.RESOLVED", dispute("RESOLVED_SELLER_FAVOUR")));
        verify(listener).onPurchaseDisputeClosed("WH-9", CAPTURE, true);

        handler.handle(payload("WH-10", "CUSTOMER.DISPUTE.RESOLVED", dispute("RESOLVED_BUYER_FAVOUR")));
        verify(listener).onPurchaseDisputeClosed("WH-10", CAPTURE, false);
    }

    @Test
    void aProcessedEventIsADuplicate() throws Exception {
        ProcessedPaypalWebhookEvent done = new ProcessedPaypalWebhookEvent();
        done.setEventId("WH-11");
        done.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSED);
        store.put("WH-11", done);

        assertThatThrownBy(() -> handler.handle(payload("WH-11", "PAYMENT.CAPTURE.COMPLETED", capture("test:q-1"))))
                .isInstanceOf(WebhookDuplicateException.class);
    }

    @Test
    void aFreshProcessingMarkIsRefusedAndAStaleOneTakenOver() throws Exception {
        ProcessedPaypalWebhookEvent running = new ProcessedPaypalWebhookEvent();
        running.setEventId("WH-12");
        running.setProcessingStatus(StripeWebhookProcessingStatus.PROCESSING);
        running.setProcessingStartedAt(Instant.now());
        store.put("WH-12", running);
        byte[] approved = ("{\"id\":\"WH-12\",\"event_type\":\"CHECKOUT.ORDER.APPROVED\",\"resource\":{\"id\":\""
                + ORDER + "\"}}").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> handler.handle(payload(approved))).isInstanceOf(WebhookProcessingException.class);

        running.setProcessingStartedAt(Instant.now().minus(Duration.ofMinutes(5)));
        when(captures.capture(checkout, false)).thenReturn(Outcome.RECORDED);
        assertThat(handler.handle(payload(approved)).processed()).isTrue();
    }

    @Test
    void unhandledEventsAreAcknowledged() throws Exception {
        WebhookResult result = handler.handle(payload("WH-13", "PAYMENT.ORDER.CREATED", "{}"));
        assertThat(result.metadata()).containsEntry("ignored", true);
    }

    @Test
    void aBadSignatureIsRefused() {
        doThrow(new PaypalSignatureException("bad")).when(verifier).verify(any(), any());
        assertThatThrownBy(() -> handler.validateSignature(payload("WH-14", "PAYMENT.CAPTURE.COMPLETED", "{}"), null))
                .isInstanceOf(WebhookSignatureException.class);
    }

    @Test
    void helpers() {
        assertThat(PaypalWebhookHandler.environmentOf("Prod:q-1")).isEqualTo("prod");
        assertThat(PaypalWebhookHandler.environmentOf("q-1")).isNull();
        assertThat(PaypalWebhookHandler.environmentOf(":q-1")).isNull();
    }

    private ReversedPurchase reversal() throws Exception {
        ArgumentCaptor<ReversedPurchase> captor = ArgumentCaptor.forClass(ReversedPurchase.class);
        verify(listener).onPurchaseReversed(captor.capture());
        return captor.getValue();
    }

    static StripePurchase row() {
        StripePurchase row = new StripePurchase();
        row.setProcessor(StripePurchase.PROCESSOR_PAYPAL);
        row.setPaymentIntentId(CAPTURE);
        row.setConnectedAccountId("SELLER123");
        row.setAmountMinor(1250L);
        return row;
    }

    static String capture(String customId) {
        return """
                {"id":"%s","status":"COMPLETED",%s"amount":{"currency_code":"USD","value":"12.50"},
                 "supplementary_data":{"related_ids":{"order_id":"%s"}},"payee":{"merchant_id":"SELLER123"}}"""
                .formatted(CAPTURE, customId == null ? "" : "\"custom_id\":\"" + customId + "\",", ORDER);
    }

    static String dispute(String outcome) {
        return """
                {"dispute_id":"PP-D-1","status":"%s","dispute_amount":{"currency_code":"USD","value":"12.50"},
                 %s"disputed_transactions":[{"seller_transaction_id":"%s","seller":{"merchant_id":"SELLER123"}}]}"""
                .formatted(outcome == null ? "OPEN" : "RESOLVED",
                        outcome == null ? "" : "\"dispute_outcome\":{\"outcome_code\":\"" + outcome + "\"},", CAPTURE);
    }

    static PaypalWebhookPayload payload(String id, String type, String resource) {
        return payload(("{\"id\":\"" + id + "\",\"event_type\":\"" + type + "\",\"resource\":" + resource + "}")
                .getBytes(StandardCharsets.UTF_8));
    }

    static PaypalWebhookPayload payload(byte[] raw) {
        try {
            var root = JSON.readTree(raw);
            return PaypalWebhookPayload.builder()
                    .eventId(root.path("id").asText())
                    .eventType(root.path("event_type").asText())
                    .eventTimestamp(Instant.now())
                    .rawBody(raw)
                    .resource(root.path("resource"))
                    .headers(Map.of())
                    .build();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
