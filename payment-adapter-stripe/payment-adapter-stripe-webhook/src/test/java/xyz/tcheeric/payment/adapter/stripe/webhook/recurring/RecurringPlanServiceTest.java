package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.webhook.StripeWebhookPayload;

/**
 * What an instalment event means for a plan. The properties pinned here are the money ones: vouchers only for paid
 * invoices, the buyer from Imani's own record, one row per invoice, and a fixed-term plan stopping at its term.
 */
@ExtendWith(MockitoExtension.class)
class RecurringPlanServiceTest {

    private static final String ACCOUNT = "acct_farm";
    private static final String BUYER = "b".repeat(64);
    private static final String ENV = StripeGatewayProperties.ENVIRONMENT_METADATA_KEY;

    @Mock private RecurringPlanRepository plans;
    @Mock private StripePurchaseRepository purchases;
    @Mock private StripeSubscriptionClient subscriptions;

    private RecurringPlanService service;
    private RecurringPlan plan;

    @BeforeEach
    void setUp() {
        service = new RecurringPlanService(plans, purchases, subscriptions, "staging");
        plan = new RecurringPlan();
        plan.setId(UUID.randomUUID());
        plan.setConnectedAccountId(ACCOUNT);
        plan.setStallPubkey("a".repeat(64));
        plan.setBuyerPubkey(BUYER);
        plan.setAmountMinor(5000);
        plan.setCurrency("gbp");
        plan.setStatus(Status.PENDING);
        lenient().when(plans.findById(plan.getId())).thenReturn(Optional.of(plan));
        lenient().when(plans.save(any(RecurringPlan.class))).thenAnswer(i -> i.getArgument(0));
    }

    private StripeWebhookPayload invoice(String invoiceId, int paid) {
        return StripeWebhookPayload.builder()
                .eventId("evt_" + invoiceId).eventType("invoice.paid")
                .checkoutSessionId(invoiceId).subscriptionId("sub_1").paymentIntentId("pi_" + invoiceId)
                .amountTotal(paid).currency("gbp").livemode(false).connectedAccountId(ACCOUNT)
                .metadata(Map.of("plan_id", plan.getId().toString(), ENV, "staging"))
                .build();
    }

    // An instalment becomes a purchase for the buyer on Imani's own plan row, keyed by the invoice id.
    @Test
    void anInstalmentBecomesAPurchaseForThePlansBuyerKeyedByTheInvoice() {
        RecurringPlanService.Instalment i = service.prepare(invoice("in_1", 5000));

        assertThat(i.purchase().checkoutSessionId()).isEqualTo("in_1");
        assertThat(i.purchase().paymentIntentId()).isEqualTo("pi_in_1");
        assertThat(i.purchase().amountMinor()).isEqualTo(5000L);
        assertThat(i.purchase().connectedAccountId()).isEqualTo(ACCOUNT);
        assertThat(i.purchase().metadata()).containsEntry("buyer_pubkey", BUYER).containsEntry("plan_id", plan.getId().toString());
    }

    // The buyer is the plan's, never something an event can name: a forged buyer_pubkey in the metadata is ignored.
    @Test
    void theBuyerComesFromThePlanNotFromEventMetadata() {
        StripeWebhookPayload forged = invoice("in_1", 5000).toBuilder()
                .metadata(Map.of("plan_id", plan.getId().toString(), ENV, "staging", "buyer_pubkey", "c".repeat(64))).build();

        assertThat(service.prepare(forged).purchase().metadata()).containsEntry("buyer_pubkey", BUYER);
    }

    // An invoice with nothing paid (a free period, a zero total) issues nothing.
    @Test
    void anInvoiceWithNothingPaidIssuesNothing() {
        assertThat(service.prepare(invoice("in_0", 0))).isNull();
    }

    // An event with no plan id is not a plan event and is left to the ordinary paths.
    @Test
    void aSubscriptionNotCreatedByImaniIsIgnored() {
        StripeWebhookPayload other = invoice("in_1", 5000).toBuilder().metadata(Map.of()).build();

        assertThat(service.prepare(other)).isNull();
        verify(subscriptions, never()).paymentIntentForInvoice(anyString(), anyString());
    }

    // A plan from another deployment on a shared Stripe account is not ours to issue for.
    @Test
    void aForeignEnvironmentPlanIsIgnored() {
        StripeWebhookPayload foreign = invoice("in_1", 5000).toBuilder()
                .metadata(Map.of("plan_id", plan.getId().toString(), ENV, "production")).build();

        assertThat(service.prepare(foreign)).isNull();
    }

    // A plan id Imani does not know is refused (retried and visible), never quietly acknowledged: money may have moved.
    @Test
    void anUnknownPlanIsRefusedNotAcknowledged() {
        StripeWebhookPayload unknown = invoice("in_1", 5000).toBuilder()
                .metadata(Map.of("plan_id", UUID.randomUUID().toString(), ENV, "staging")).build();

        assertThatThrownBy(() -> service.prepare(unknown)).isInstanceOf(RecurringPlanException.class);
    }

    // An instalment that arrives on another account than the plan's is refused, not issued.
    @Test
    void anInvoiceFromAnotherAccountIsRefused() {
        StripeWebhookPayload wrong = invoice("in_1", 5000).toBuilder().connectedAccountId("acct_other").build();

        assertThatThrownBy(() -> service.prepare(wrong)).isInstanceOf(RecurringPlanException.class);
    }

    // A currency that differs from the plan's would issue vouchers in the wrong unit, so it is refused.
    @Test
    void aCurrencyMismatchIsRefused() {
        StripeWebhookPayload usd = invoice("in_1", 5000).toBuilder().currency("usd").build();

        assertThatThrownBy(() -> service.prepare(usd)).isInstanceOf(RecurringPlanException.class);
    }

    // Paid means owed: if the stall changed its price, the vouchers follow what was actually paid.
    @Test
    void aDifferentPaidAmountIsIssuedAsPaid() {
        assertThat(service.prepare(invoice("in_1", 6000)).purchase().amountMinor()).isEqualTo(6000L);
    }

    // A newer API version puts no payment_intent on the invoice, so it is read from the invoice's payments.
    @Test
    void theMissingPaymentIntentIsReadFromTheInvoicesPayments() {
        when(subscriptions.paymentIntentForInvoice(ACCOUNT, "in_1")).thenReturn("pi_from_payments");
        StripeWebhookPayload noPi = invoice("in_1", 5000).toBuilder().paymentIntentId(null).build();

        assertThat(service.prepare(noPi).purchase().paymentIntentId()).isEqualTo("pi_from_payments");
    }

    // With no payment intent at all the row would be unreachable by refunds and disputes, so it is refused and retried.
    @Test
    void noPaymentIntentAnywhereIsRefusedSoItIsRetried() {
        when(subscriptions.paymentIntentForInvoice(ACCOUNT, "in_1")).thenReturn(null);
        StripeWebhookPayload noPi = invoice("in_1", 5000).toBuilder().paymentIntentId(null).build();

        assertThatThrownBy(() -> service.prepare(noPi)).isInstanceOf(RecurringPlanException.class);
    }

    // The first paid instalment makes a PENDING plan ACTIVE and remembers its subscription.
    @Test
    void theFirstPaidInstalmentActivatesThePlan() {
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(1L);

        service.recorded(plan, invoice("in_1", 5000));

        assertThat(plan.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(plan.getSubscriptionId()).isEqualTo("sub_1");
        verify(subscriptions, never()).cancelAtPeriodEnd(anyString(), anyString());
    }

    // The Nth paid instalment of a fixed-term plan ends the subscription, so the buyer is never charged one too many.
    @Test
    void theLastInstalmentOfAFixedTermPlanEndsTheSubscription() {
        plan.setTotalInstalments(3);
        plan.setStatus(Status.ACTIVE);
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(3L);

        service.recorded(plan, invoice("in_3", 5000));

        verify(subscriptions).cancelAtPeriodEnd(ACCOUNT, "sub_1");
        assertThat(plan.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(plan.getCancelRequestedAt()).isNotNull();
    }

    // Before the last instalment nothing is cancelled.
    @Test
    void anEarlierInstalmentOfAFixedTermPlanDoesNotEndIt() {
        plan.setTotalInstalments(3);
        plan.setStatus(Status.ACTIVE);
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(2L);

        service.recorded(plan, invoice("in_2", 5000));

        verify(subscriptions, never()).cancelAtPeriodEnd(anyString(), anyString());
        assertThat(plan.getStatus()).isEqualTo(Status.ACTIVE);
    }

    // The count is of recorded rows, so a redelivered last instalment cannot count twice or cancel twice.
    @Test
    void aRedeliveredLastInstalmentCancelsOnlyOnce() {
        plan.setTotalInstalments(3);
        plan.setStatus(Status.ACTIVE);
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(3L);

        service.recorded(plan, invoice("in_3", 5000));
        service.recorded(plan, invoice("in_3", 5000));

        verify(subscriptions, times(1)).cancelAtPeriodEnd(ACCOUNT, "sub_1");
    }

    // If ending the subscription fails, it throws (so the event is retried) and the plan is not marked cancelled.
    @Test
    void aFailedCancelIsRetriedNotSwallowed() {
        plan.setTotalInstalments(3);
        plan.setStatus(Status.ACTIVE);
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(3L);
        org.mockito.Mockito.doThrow(new RecurringPlanException("stripe down"))
                .when(subscriptions).cancelAtPeriodEnd(ACCOUNT, "sub_1");

        assertThatThrownBy(() -> service.recorded(plan, invoice("in_3", 5000))).isInstanceOf(RecurringPlanException.class);

        assertThat(plan.getCancelRequestedAt()).isNull();
    }

    // A payment after the plan ended, or past its term, is recorded but flagged for a person, never auto-refunded.
    @Test
    void aChargeAfterTheTermOrAfterCancellationNeedsAttention() {
        plan.setTotalInstalments(2);
        plan.setStatus(Status.ACTIVE);
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(3L);

        service.recorded(plan, invoice("in_3", 5000));
        assertThat(plan.getStatus()).isEqualTo(Status.ATTENTION);

        RecurringPlan cancelled = new RecurringPlan();
        cancelled.setId(UUID.randomUUID());
        cancelled.setConnectedAccountId(ACCOUNT);
        cancelled.setStatus(Status.CANCELLED);
        when(purchases.countByRecurringPlanId(cancelled.getId())).thenReturn(1L);
        service.recorded(cancelled, invoice("in_9", 5000));
        assertThat(cancelled.getStatus()).isEqualTo(Status.ATTENTION);
    }

    // A failed instalment makes the plan past due and issues nothing.
    @Test
    void aFailedInstalmentMakesThePlanPastDue() {
        plan.setStatus(Status.ACTIVE);

        service.paymentFailed(invoice("in_2", 0).toBuilder().eventType("invoice.payment_failed").build());

        assertThat(plan.getStatus()).isEqualTo(Status.PAST_DUE);
    }

    // The next paid instalment brings a past-due plan back to active.
    @Test
    void aPaidInstalmentRecoversAPastDuePlan() {
        plan.setStatus(Status.PAST_DUE);
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(2L);

        service.recorded(plan, invoice("in_2", 5000));

        assertThat(plan.getStatus()).isEqualTo(Status.ACTIVE);
    }

    // A subscription that ended with every instalment paid is COMPLETED; otherwise it was CANCELLED.
    @Test
    void anEndedSubscriptionIsCompletedOnlyIfEveryInstalmentWasPaid() {
        plan.setTotalInstalments(3);
        plan.setStatus(Status.ACTIVE);
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(2L);
        StripeWebhookPayload ended = invoice("x", 0).toBuilder().eventType("customer.subscription.deleted").build();

        service.subscriptionEnded(ended);
        assertThat(plan.getStatus()).isEqualTo(Status.CANCELLED);

        plan.setStatus(Status.ACTIVE);
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(3L);
        service.subscriptionEnded(ended);
        assertThat(plan.getStatus()).isEqualTo(Status.COMPLETED);
    }

    // The session of a plan only links the subscription: it issues nothing (the first invoice does).
    @Test
    void aPlanSessionLinksTheSubscriptionAndIssuesNothing() {
        StripeWebhookPayload session = invoice("cs_1", 5000).toBuilder().eventType("checkout.session.completed")
                .subscriptionId("sub_9").build();

        service.checkoutCompleted(session);

        assertThat(plan.getSubscriptionId()).isEqualTo("sub_9");
        assertThat(plan.getCheckoutSessionId()).isEqualTo("cs_1");
        verify(purchases, never()).save(any());
    }

    // An abandoned Stripe page closes its PENDING plan, so it does not linger.
    @Test
    void anExpiredPlanSessionClosesAPendingPlan() {
        service.checkoutExpired(invoice("cs_1", 0).toBuilder().eventType("checkout.session.expired").paymentIntentId(null).build());

        assertThat(plan.getStatus()).isEqualTo(Status.CANCELLED);
    }

    // An expired session never touches a plan that already has a subscription: that one is paying.
    @Test
    void anExpiredSessionLeavesAPlanWithASubscriptionAlone() {
        plan.setSubscriptionId("sub_1");
        plan.setStatus(Status.ACTIVE);

        service.checkoutExpired(invoice("cs_1", 0).toBuilder().eventType("checkout.session.expired").build());

        assertThat(plan.getStatus()).isEqualTo(Status.ACTIVE);
    }
}
