package xyz.tcheeric.payment.adapter.paypal.webhook.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSale;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.PaidPurchase;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.ReversedPurchase;

class PaypalRecurringPlanServiceTest {

    static final String SUBSCRIPTION = "I-BW452GLLEP1G";
    static final String MERCHANT = "SELLER123";
    static final String BUYER = "b".repeat(64);
    static final String STALL = "a".repeat(64);

    RecurringPlanRepository plans;
    StripePurchaseRepository purchases;
    StripePurchaseListener listener;
    PaypalRecurringPlanService service;
    RecurringPlan plan;

    static RecurringPlan plan(Status status, Integer total) {
        RecurringPlan plan = new RecurringPlan();
        plan.setId(UUID.randomUUID());
        plan.setProcessor(RecurringPlan.PROCESSOR_PAYPAL);
        plan.setConnectedAccountId(MERCHANT);
        plan.setStallPubkey(STALL);
        plan.setBuyerPubkey(BUYER);
        plan.setAmountMinor(5000);
        plan.setCurrency("gbp");
        plan.setIntervalUnit("month");
        plan.setTotalInstalments(total);
        plan.setStatus(status);
        plan.setSubscriptionId(SUBSCRIPTION);
        plan.setCreatedAt(Instant.now());
        plan.setUpdatedAt(Instant.now());
        return plan;
    }

    static PaypalSale sale(String id, String state) {
        return new PaypalSale(id, state, 5000L, "gbp", SUBSCRIPTION, Instant.now());
    }

    @BeforeEach
    void setUp() {
        plans = mock(RecurringPlanRepository.class);
        purchases = mock(StripePurchaseRepository.class);
        listener = mock(StripePurchaseListener.class);
        service = new PaypalRecurringPlanService(plans, purchases, listener, "test", false);
        plan = plan(Status.ACTIVE, 6);
        lenient().when(plans.findByProcessorAndSubscriptionId(RecurringPlan.PROCESSOR_PAYPAL, SUBSCRIPTION))
                .thenReturn(Optional.of(plan));
        lenient().when(plans.findById(plan.getId())).thenReturn(Optional.of(plan));
    }

    // A paid instalment is recorded for the buyer on Imani's plan row, keyed by the sale, and linked to the plan.
    @Test
    void aPaidInstalmentIsRecordedForThePlansBuyer() throws Exception {
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(1L);

        assertThat(service.salePaid(sale("SALE-1", "completed"))).isTrue();

        ArgumentCaptor<PaidPurchase> paid = ArgumentCaptor.forClass(PaidPurchase.class);
        verify(listener).onPurchasePaid(paid.capture());
        assertThat(paid.getValue().eventId()).isEqualTo("paypal:sale:SALE-1");
        assertThat(paid.getValue().paymentIntentId()).isEqualTo("SALE-1");
        assertThat(paid.getValue().isPaypal()).isTrue();
        assertThat(paid.getValue().stallPubkey()).isEqualTo(STALL);
        assertThat(paid.getValue().metadata()).containsEntry("buyer_pubkey", BUYER)
                .containsEntry("plan_id", plan.getId().toString());
        assertThat(plan.getStatus()).isEqualTo(Status.ACTIVE);
    }

    // A sale for a subscription that is not one of Imani's plans is left alone.
    @Test
    void aSaleOfAnotherSubscriptionIsNotOurs() throws Exception {
        PaypalSale foreign = new PaypalSale("SALE-9", "completed", 5000L, "gbp", "I-OTHER", Instant.now());

        assertThat(service.salePaid(foreign)).isFalse();

        verify(listener, never()).onPurchasePaid(any());
    }

    // A sale in another currency than the plan's is refused so the event is retried and seen by a person.
    @Test
    void aSaleInTheWrongCurrencyIsRefused() {
        PaypalSale euros = new PaypalSale("SALE-2", "completed", 5000L, "eur", SUBSCRIPTION, Instant.now());

        assertThatThrownBy(() -> service.salePaid(euros)).isInstanceOf(IllegalStateException.class);
    }

    // The last instalment of a fixed-term plan completes it.
    @Test
    void theLastInstalmentCompletesTheTerm() {
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(6L);

        service.salePaid(sale("SALE-6", "completed"));

        assertThat(plan.getStatus()).isEqualTo(Status.COMPLETED);
    }

    // A buyer charged after the plan was cancelled is recorded (paid means owed) and flagged for attention.
    @Test
    void aChargeAfterCancellationIsFlagged() {
        plan.setStatus(Status.CANCELLED);
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(2L);

        service.salePaid(sale("SALE-3", "completed"));

        assertThat(plan.getStatus()).isEqualTo(Status.ATTENTION);
    }

    // A sale the reconciler finds already refunded is recorded and then reversed, so it is never owed.
    @Test
    void aRefundedSaleFoundLateIsRecordedThenReversed() throws Exception {
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(1L);

        service.salePaid(sale("SALE-4", "refunded"));

        ArgumentCaptor<ReversedPurchase> reversed = ArgumentCaptor.forClass(ReversedPurchase.class);
        verify(listener).onPurchaseReversed(reversed.capture());
        assertThat(reversed.getValue().refundedAmountMinor()).isEqualTo(5000L);
    }

    // A listener refusal surfaces as an exception, so the webhook is not marked processed.
    @Test
    void aListenerRefusalIsNotSwallowed() throws Exception {
        doThrow(new Exception("db down")).when(listener).onPurchasePaid(any());

        assertThatThrownBy(() -> service.salePaid(sale("SALE-5", "completed")))
                .isInstanceOf(IllegalStateException.class);
    }

    // Activation moves a pending plan to active without issuing anything.
    @Test
    void activationIssuesNothing() throws Exception {
        plan.setStatus(Status.PENDING);

        service.activated(SUBSCRIPTION);

        assertThat(plan.getStatus()).isEqualTo(Status.ACTIVE);
        verify(listener, never()).onPurchasePaid(any());
    }

    // A failed instalment puts the plan past due.
    @Test
    void aFailedInstalmentIsPastDue() {
        service.paymentFailed(SUBSCRIPTION);

        assertThat(plan.getStatus()).isEqualTo(Status.PAST_DUE);
    }

    // An ended subscription is cancelled when instalments are missing, completed when all were paid.
    @Test
    void anEndedSubscriptionIsCancelledOrCompleted() {
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(3L);
        service.ended(SUBSCRIPTION);
        assertThat(plan.getStatus()).isEqualTo(Status.CANCELLED);

        plan.setStatus(Status.ACTIVE);
        when(purchases.countByRecurringPlanId(plan.getId())).thenReturn(6L);
        service.ended(SUBSCRIPTION);
        assertThat(plan.getStatus()).isEqualTo(Status.COMPLETED);
    }

    // A refunded instalment flags its plan for a person, without cancelling anything.
    @Test
    void aReturnedInstalmentFlagsThePlan() {
        StripePurchase row = new StripePurchase();
        row.setRecurringPlanId(plan.getId());
        when(purchases.findByPaymentIntentId("SALE-1")).thenReturn(Optional.of(row));

        service.instalmentReturned("SALE-1");

        assertThat(plan.getStatus()).isEqualTo(Status.ATTENTION);
    }
}
