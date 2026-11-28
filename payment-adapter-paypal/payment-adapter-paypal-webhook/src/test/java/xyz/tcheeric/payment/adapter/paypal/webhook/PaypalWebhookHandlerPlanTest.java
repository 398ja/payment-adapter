package xyz.tcheeric.payment.adapter.paypal.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ProcessedPaypalWebhookEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeWebhookProcessingStatus;
import xyz.tcheeric.payment.adapter.core.model.repository.PaypalCheckoutRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedPaypalWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSale;
import xyz.tcheeric.payment.adapter.paypal.gateway.webhook.PaypalWebhookVerifier;
import xyz.tcheeric.payment.adapter.paypal.webhook.recurring.PaypalRecurringPlanService;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.ReversedPurchase;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

/** The instalment-plan events on the payments webhook (ADR 0003). */
class PaypalWebhookHandlerPlanTest {

    static final String SALE = "80021663DE681814L";
    static final String SUB = "I-BW452GLLEP1G";

    ProcessedPaypalWebhookEventRepository events;
    StripePurchaseRepository purchases;
    StripePurchaseListener listener;
    PaypalRecurringPlanService plans;
    PaypalWebhookHandler handler;
    Map<String, ProcessedPaypalWebhookEvent> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        events = mock(ProcessedPaypalWebhookEventRepository.class);
        purchases = mock(StripePurchaseRepository.class);
        listener = mock(StripePurchaseListener.class);
        plans = mock(PaypalRecurringPlanService.class);
        handler = new PaypalWebhookHandler(mock(PaypalWebhookVerifier.class), events,
                mock(PaypalCheckoutRepository.class), purchases, mock(PaypalCaptureService.class), listener, "test",
                false);
        handler.setRecurringPlans(plans);
        lenient().when(events.save(any())).thenAnswer(inv -> {
            ProcessedPaypalWebhookEvent e = inv.getArgument(0);
            store.put(e.getEventId(), e);
            return e;
        });
        lenient().when(events.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
    }

    static String sale(String state) {
        return "{\"id\":\"" + SALE + "\",\"state\":\"" + state + "\",\"amount\":{\"total\":\"50.00\",\"currency\":\"GBP\"},"
                + "\"billing_agreement_id\":\"" + SUB + "\",\"create_time\":\"2027-01-01T00:00:00Z\"}";
    }

    // A paid instalment is read from the sale and handed to the plan service, then the event is processed.
    @Test
    void aPaidInstalmentIsRecorded() throws Exception {
        when(plans.salePaid(any())).thenReturn(true);

        WebhookResult result = handler.handle(PaypalWebhookHandlerTest.payload("WH-1", "PAYMENT.SALE.COMPLETED",
                sale("completed")));

        ArgumentCaptor<PaypalSale> read = ArgumentCaptor.forClass(PaypalSale.class);
        verify(plans).salePaid(read.capture());
        assertThat(read.getValue().subscriptionId()).isEqualTo(SUB);
        assertThat(read.getValue().amountMinor()).isEqualTo(5000L);
        assertThat(read.getValue().currency()).isEqualTo("gbp");
        assertThat(result.metadata()).containsEntry("reference", SALE);
        assertThat(store.get("WH-1").getProcessingStatus()).isEqualTo(StripeWebhookProcessingStatus.PROCESSED);
    }

    // An instalment that could not be recorded is failed, so PayPal redelivers it.
    @Test
    void anUnrecordedInstalmentIsRetried() {
        when(plans.salePaid(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> handler.handle(PaypalWebhookHandlerTest.payload("WH-2", "PAYMENT.SALE.COMPLETED",
                sale("completed")))).isInstanceOf(WebhookProcessingException.class);
        assertThat(store.get("WH-2").getProcessingStatus()).isEqualTo(StripeWebhookProcessingStatus.FAILED);
    }

    // Without instalment plans switched on, a sale is acknowledged and nothing is recorded.
    @Test
    void withoutPlansASaleIsAcknowledged() throws Exception {
        handler.setRecurringPlans(null);

        WebhookResult result = handler.handle(PaypalWebhookHandlerTest.payload("WH-3", "PAYMENT.SALE.COMPLETED",
                sale("completed")));

        assertThat(result.metadata()).containsEntry("ignored", true);
    }

    // A refunded instalment reverses its ledger row with the cumulative figure and flags the plan.
    @Test
    void aRefundedInstalmentIsReversed() throws Exception {
        StripePurchase row = new StripePurchase();
        row.setProcessor(StripePurchase.PROCESSOR_PAYPAL);
        row.setAmountMinor(5000L);
        row.setCurrency("gbp");
        row.setConnectedAccountId("SELLER123");
        when(purchases.findByPaymentIntentId(SALE)).thenReturn(Optional.of(row));

        handler.handle(PaypalWebhookHandlerTest.payload("WH-4", "PAYMENT.SALE.REFUNDED",
                "{\"id\":\"REF-1\",\"sale_id\":\"" + SALE + "\",\"amount\":{\"total\":\"10.00\",\"currency\":\"GBP\"},"
                        + "\"total_refunded_amount\":{\"value\":\"20.00\",\"currency\":\"GBP\"}}"));

        ArgumentCaptor<ReversedPurchase> reversed = ArgumentCaptor.forClass(ReversedPurchase.class);
        verify(listener).onPurchaseReversed(reversed.capture());
        assertThat(reversed.getValue().paymentIntentId()).isEqualTo(SALE);
        assertThat(reversed.getValue().refundedAmountMinor()).isEqualTo(2000L);
        verify(plans).instalmentReturned(SALE);
    }

    // Regression: a refund that overtakes its sale is still handed to the ledger, never silently acknowledged.
    @Test
    void aRefundBeforeItsSaleIsStillRecorded() throws Exception {
        when(purchases.findByPaymentIntentId(SALE)).thenReturn(Optional.empty());

        handler.handle(PaypalWebhookHandlerTest.payload("WH-8", "PAYMENT.SALE.REFUNDED",
                "{\"id\":\"REF-2\",\"sale_id\":\"" + SALE + "\",\"amount\":{\"total\":\"50.00\",\"currency\":\"GBP\"}}"));

        ArgumentCaptor<ReversedPurchase> reversed = ArgumentCaptor.forClass(ReversedPurchase.class);
        verify(listener).onPurchaseReversed(reversed.capture());
        assertThat(reversed.getValue().paymentIntentId()).isEqualTo(SALE);
        assertThat(reversed.getValue().refundedAmountMinor()).isEqualTo(5000L);
    }

    // Regression: a reversal (PayPal took the money back) is a full refund of the sale.
    @Test
    void aReversalIsAFullRefund() throws Exception {
        when(purchases.findByPaymentIntentId(SALE)).thenReturn(Optional.empty());

        handler.handle(PaypalWebhookHandlerTest.payload("WH-10", "PAYMENT.SALE.REVERSED", sale("reversed")));

        ArgumentCaptor<ReversedPurchase> reversed = ArgumentCaptor.forClass(ReversedPurchase.class);
        verify(listener).onPurchaseReversed(reversed.capture());
        assertThat(reversed.getValue().refundedAmountMinor()).isEqualTo(5000L);
        verify(plans).instalmentReturned(SALE);
    }

    // Regression: a dispute on an instalment flags its plan, as a refund does.
    @Test
    void aDisputedInstalmentFlagsThePlan() throws Exception {
        handler.handle(PaypalWebhookHandlerTest.payload("WH-9", "CUSTOMER.DISPUTE.CREATED",
                "{\"dispute_id\":\"PP-D-1\",\"dispute_amount\":{\"currency_code\":\"GBP\",\"value\":\"50.00\"},"
                        + "\"disputed_transactions\":[{\"seller_transaction_id\":\"" + SALE + "\","
                        + "\"seller\":{\"merchant_id\":\"SELLER123\"}}]}"));

        verify(plans).instalmentReturned(SALE);
    }

    // Subscription lifecycle events reach the plan service by subscription id.
    @Test
    void lifecycleEventsReachThePlan() throws Exception {
        String subscription = "{\"id\":\"" + SUB + "\"}";
        handler.handle(PaypalWebhookHandlerTest.payload("WH-5", "BILLING.SUBSCRIPTION.ACTIVATED", subscription));
        handler.handle(PaypalWebhookHandlerTest.payload("WH-6", "BILLING.SUBSCRIPTION.PAYMENT.FAILED", subscription));
        handler.handle(PaypalWebhookHandlerTest.payload("WH-7", "BILLING.SUBSCRIPTION.EXPIRED", subscription));

        verify(plans).activated(SUB);
        verify(plans).paymentFailed(SUB);
        verify(plans).ended(SUB);
        verify(listener, never()).onPurchasePaid(any());
    }
}
