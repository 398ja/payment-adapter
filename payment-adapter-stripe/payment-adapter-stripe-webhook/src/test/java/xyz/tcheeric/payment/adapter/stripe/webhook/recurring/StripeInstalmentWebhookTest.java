package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedStripeWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePaymentReferenceRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.StripeWebhookHandler;
import xyz.tcheeric.payment.adapter.stripe.webhook.StripeWebhookPayload;
import xyz.tcheeric.payment.adapter.stripe.webhook.service.StripeWebhookSignatureVerifier;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;

/**
 * Subscription events through the real handler. Two things matter most here: an instalment is issued exactly once
 * (the plan's Checkout session must NOT also be recorded as a purchase), and a plan event is never quietly
 * acknowledged when Imani cannot act on it.
 */
class StripeInstalmentWebhookTest {

    private static final String ACCOUNT = "acct_farm";

    private StripeWebhookHandler handler;
    private StripePurchaseListener listener;
    private RecurringPlanService plans;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        ProcessedStripeWebhookEventRepository processed = mock(ProcessedStripeWebhookEventRepository.class);
        when(processed.findById(any())).thenReturn(Optional.empty());
        when(processed.save(any())).thenAnswer(i -> i.getArgument(0));
        handler = new StripeWebhookHandler(processed, mock(StripePaymentReferenceRepository.class),
                mock(QuoteClient.class), mock(PaymentClient.class), mock(StripeWebhookSignatureVerifier.class));
        listener = mock(StripePurchaseListener.class);
        plans = mock(RecurringPlanService.class);
        request = mock(HttpServletRequest.class);
        handler.setPurchaseListener(listener);
    }

    private StripeWebhookPayload parse(String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        when(request.getInputStream()).thenReturn(new ServletInputStream() {
            private final ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener readListener) { }
            @Override public int read() { return in.read(); }
        });
        return handler.parsePayload(request);
    }

    private static String invoiceEvent(String subscriptionShape) {
        return "{\"id\":\"evt_1\",\"type\":\"invoice.paid\",\"created\":1,\"livemode\":false,\"account\":\"" + ACCOUNT
                + "\",\"data\":{\"object\":{\"id\":\"in_1\",\"amount_paid\":5000,\"currency\":\"gbp\"," + subscriptionShape + "}}}";
    }

    // Newer API versions: the subscription and its metadata sit under parent.subscription_details.
    @Test
    void parsesAnInvoiceInTheNewerShape() throws Exception {
        StripeWebhookPayload p = parse(invoiceEvent(
                "\"parent\":{\"subscription_details\":{\"subscription\":\"sub_1\",\"metadata\":{\"plan_id\":\"p1\"}}}"));

        assertThat(p.getCheckoutSessionId()).isEqualTo("in_1");
        assertThat(p.getSubscriptionId()).isEqualTo("sub_1");
        assertThat(p.getAmountTotal()).isEqualTo(5000);
        assertThat(p.getMetadata()).containsEntry("plan_id", "p1");
        assertThat(p.getConnectedAccountId()).isEqualTo(ACCOUNT);
    }

    // Older API versions: the subscription is top level and its metadata is under subscription_details.
    @Test
    void parsesAnInvoiceInTheOlderShape() throws Exception {
        StripeWebhookPayload p = parse(invoiceEvent(
                "\"subscription\":\"sub_2\",\"payment_intent\":\"pi_2\",\"subscription_details\":{\"metadata\":{\"plan_id\":\"p2\"}}"));

        assertThat(p.getSubscriptionId()).isEqualTo("sub_2");
        assertThat(p.getPaymentIntentId()).isEqualTo("pi_2");
        assertThat(p.getMetadata()).containsEntry("plan_id", "p2");
    }

    // The invoice's OWN metadata is not Imani's: a plan id placed there must not make an invoice a plan event.
    @Test
    void anInvoicesOwnMetadataDoesNotNameAPlan() throws Exception {
        StripeWebhookPayload p = parse(invoiceEvent("\"metadata\":{\"plan_id\":\"forged\"}"));

        assertThat(p.getMetadata() == null ? Map.of() : p.getMetadata()).doesNotContainKey("plan_id");
    }

    // A subscription-deleted event names the plan through the subscription's own metadata.
    @Test
    void parsesASubscriptionDeletedEvent() throws Exception {
        StripeWebhookPayload p = parse("{\"id\":\"evt_9\",\"type\":\"customer.subscription.deleted\",\"created\":1,"
                + "\"account\":\"" + ACCOUNT + "\",\"data\":{\"object\":{\"id\":\"sub_9\",\"metadata\":{\"plan_id\":\"p9\"}}}}");

        assertThat(p.getSubscriptionId()).isEqualTo("sub_9");
        assertThat(p.getMetadata()).containsEntry("plan_id", "p9");
    }

    private StripeWebhookPayload paidInvoice() {
        return StripeWebhookPayload.builder().eventId("evt_1").eventType("invoice.paid").rawPayload("{\"id\":\"evt_1\"}")
                .checkoutSessionId("in_1").subscriptionId("sub_1").paymentIntentId("pi_1").amountTotal(5000).currency("gbp")
                .connectedAccountId(ACCOUNT).metadata(Map.of("plan_id", "p1")).build();
    }

    // The instalment goes to the listener first, and only then is the plan updated (and a final instalment cancelled).
    @Test
    void anInstalmentIsRecordedBeforeThePlanIsUpdated() throws Exception {
        handler.setRecurringPlans(plans);
        var instalment = new RecurringPlanService.Instalment(new xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan(),
                new StripePurchaseListener.PaidPurchase("evt_1", "in_1", "pi_1", ACCOUNT, 5000L, "gbp", Map.of("plan_id", "p1"), false));
        when(plans.prepare(any())).thenReturn(instalment);

        handler.handle(paidInvoice());

        InOrder order = inOrder(listener, plans);
        order.verify(listener).onPurchasePaid(instalment.purchase());
        order.verify(plans).recorded(any(), any());
    }

    // If the listener cannot record, the plan is NOT updated and the event is refused so Stripe redelivers it.
    @Test
    void aListenerFailureLeavesThePlanAloneAndRefusesTheEvent() throws Exception {
        handler.setRecurringPlans(plans);
        var instalment = new RecurringPlanService.Instalment(new xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan(),
                new StripePurchaseListener.PaidPurchase("evt_1", "in_1", "pi_1", ACCOUNT, 5000L, "gbp", Map.of("plan_id", "p1"), false));
        when(plans.prepare(any())).thenReturn(instalment);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(listener).onPurchasePaid(any());

        assertThatThrownBy(() -> handler.handle(paidInvoice())).isInstanceOf(WebhookProcessingException.class);

        verify(plans, never()).recorded(any(), any());
    }

    // With plans off, an invoice naming a plan is REFUSED, not acknowledged: a paid instalment must not vanish.
    @Test
    void aPlanInvoiceIsRefusedWhenPlansAreOff() {
        assertThatThrownBy(() -> handler.handle(paidInvoice())).isInstanceOf(WebhookProcessingException.class);

        verifyNoInteractions(listener);
    }

    // With plans off, an invoice that is NOT a plan's (no plan id) is simply acknowledged and ignored.
    @Test
    void aNonPlanInvoiceIsIgnoredWhenPlansAreOff() throws Exception {
        handler.handle(paidInvoice().toBuilder().metadata(Map.of()).build());

        verifyNoInteractions(listener);
    }

    // The plan's Checkout session links the subscription and is NEVER recorded as a purchase: the first
    // instalment is issued from its invoice, so recording the session too would issue it twice.
    @Test
    void aPlanSessionIsNeverRecordedAsAPurchase() throws Exception {
        handler.setRecurringPlans(plans);
        StripeWebhookPayload session = StripeWebhookPayload.builder().eventId("evt_s").eventType("checkout.session.completed")
                .rawPayload("{\"id\":\"evt_s\"}").checkoutSessionId("cs_1").subscriptionId("sub_1").paymentIntentId(null)
                .amountTotal(5000).currency("gbp").paymentStatus("paid").connectedAccountId(ACCOUNT)
                .metadata(Map.of("plan_id", "p1")).build();

        handler.handle(session);

        verify(plans).checkoutCompleted(any());
        verifyNoInteractions(listener);
    }

    // With plans off, a plan's Checkout session is refused too, rather than recorded as a one-off purchase.
    @Test
    void aPlanSessionIsRefusedWhenPlansAreOff() {
        StripeWebhookPayload session = StripeWebhookPayload.builder().eventId("evt_s").eventType("checkout.session.completed")
                .rawPayload("{\"id\":\"evt_s\"}").checkoutSessionId("cs_1").paymentStatus("paid").connectedAccountId(ACCOUNT)
                .amountTotal(5000).currency("gbp").metadata(Map.of("plan_id", "p1")).build();

        assertThatThrownBy(() -> handler.handle(session)).isInstanceOf(WebhookProcessingException.class);
        verifyNoInteractions(listener);
    }

    // A payment failure and a subscription ending are bookkeeping only: nothing reaches the listener.
    @Test
    void failuresAndEndingsIssueNothing() throws Exception {
        handler.setRecurringPlans(plans);

        handler.handle(paidInvoice().toBuilder().eventId("evt_f").eventType("invoice.payment_failed").build());
        handler.handle(paidInvoice().toBuilder().eventId("evt_d").eventType("customer.subscription.deleted").build());

        verify(plans).paymentFailed(any());
        verify(plans).subscriptionEnded(any());
        verifyNoInteractions(listener);
    }

    // The ledger key for an instalment is its invoice id, carried through to the listener unchanged.
    @Test
    void theLedgerKeyIsTheInvoiceId() throws Exception {
        handler.setRecurringPlans(plans);
        var purchase = new StripePurchaseListener.PaidPurchase("evt_1", "in_1", "pi_1", ACCOUNT, 5000L, "gbp", Map.of("plan_id", "p1"), false);
        when(plans.prepare(any())).thenReturn(new RecurringPlanService.Instalment(
                new xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan(), purchase));

        handler.handle(paidInvoice());

        ArgumentCaptor<StripePurchaseListener.PaidPurchase> captor = ArgumentCaptor.forClass(StripePurchaseListener.PaidPurchase.class);
        verify(listener).onPurchasePaid(captor.capture());
        assertThat(captor.getValue().checkoutSessionId()).isEqualTo("in_1");
    }
}
