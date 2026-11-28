package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

/** Cancelling a plan: only its buyer, only before it ends, and always at period end on the stall's account. */
class PlanManagementControllerTest {

    private static final String BUYER = "b".repeat(64);

    private RecurringPlanRepository plans;
    private StripeSubscriptionClient subscriptions;
    private PlanManagementController controller;
    private RecurringPlan plan;

    @BeforeEach
    void setUp() {
        plans = mock(RecurringPlanRepository.class);
        subscriptions = mock(StripeSubscriptionClient.class);
        controller = new PlanManagementController(plans, mock(StripePurchaseRepository.class), subscriptions);
        plan = new RecurringPlan();
        plan.setId(UUID.randomUUID());
        plan.setBuyerPubkey(BUYER);
        plan.setConnectedAccountId("acct_farm");
        plan.setSubscriptionId("sub_1");
        plan.setStatus(Status.ACTIVE);
        plan.setCurrency("gbp");
        when(plans.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(plans.save(any(RecurringPlan.class))).thenAnswer(i -> i.getArgument(0));
    }

    private HttpStatus cancel(String id, String buyer) {
        return HttpStatus.valueOf(controller.cancel(id, new PlanManagementController.CancelRequest(buyer)).getStatusCode().value());
    }

    // The buyer's cancel ends the subscription at period end on the stall's account, and records that it was asked.
    @Test
    void theBuyerCancelsAtPeriodEndOnTheStallsAccount() {
        assertThat(cancel(plan.getId().toString(), BUYER)).isEqualTo(HttpStatus.OK);

        verify(subscriptions).cancelAtPeriodEnd("acct_farm", "sub_1");
        assertThat(plan.getCancelRequestedAt()).isNotNull();
    }

    // Somebody else's key gets 404, exactly like an unknown plan, so a plan id cannot be probed.
    @Test
    void aDifferentBuyerGetsNotFoundAndNothingIsCancelled() {
        assertThat(cancel(plan.getId().toString(), "c".repeat(64))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(cancel(UUID.randomUUID().toString(), BUYER)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(cancel("not-a-uuid", BUYER)).isEqualTo(HttpStatus.NOT_FOUND);

        verify(subscriptions, never()).cancelAtPeriodEnd(anyString(), anyString());
    }

    // A plan that already ended cannot be cancelled again.
    @Test
    void anEndedPlanCannotBeCancelledAgain() {
        plan.setStatus(Status.COMPLETED);
        assertThat(cancel(plan.getId().toString(), BUYER)).isEqualTo(HttpStatus.CONFLICT);
        plan.setStatus(Status.CANCELLED);
        assertThat(cancel(plan.getId().toString(), BUYER)).isEqualTo(HttpStatus.CONFLICT);

        verify(subscriptions, never()).cancelAtPeriodEnd(anyString(), anyString());
    }

    // An orphaned plan cannot be cancelled from here: saying "cancelled" would be false, the stall must do it.
    @Test
    void anOrphanedPlanIsNotPretendedCancelled() {
        plan.setStatus(Status.ORPHANED);

        assertThat(cancel(plan.getId().toString(), BUYER)).isEqualTo(HttpStatus.CONFLICT);
        verify(subscriptions, never()).cancelAtPeriodEnd(anyString(), anyString());
        assertThat(plan.getStatus()).isEqualTo(Status.ORPHANED);
    }

    // If Stripe cannot be reached, the buyer is told, and the plan is not marked as cancel-requested.
    @Test
    void aStripeFailureIsReportedAndNotRecorded() {
        doThrow(new RecurringPlanException("down")).when(subscriptions).cancelAtPeriodEnd(anyString(), anyString());

        assertThat(cancel(plan.getId().toString(), BUYER)).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(plan.getCancelRequestedAt()).isNull();
    }

    // A plan the buyer never finished paying for has no subscription, so cancelling just closes it.
    @Test
    void cancellingAPendingPlanWithNoSubscriptionJustClosesIt() {
        plan.setSubscriptionId(null);
        plan.setStatus(Status.PENDING);

        assertThat(cancel(plan.getId().toString(), BUYER)).isEqualTo(HttpStatus.OK);

        assertThat(plan.getStatus()).isEqualTo(Status.CANCELLED);
        verify(subscriptions, never()).cancelAtPeriodEnd(anyString(), anyString());
    }

    // Reading a plan needs the buyer's key too.
    @Test
    void readingAPlanNeedsTheBuyersKey() {
        assertThat(controller.get(plan.getId().toString(), BUYER).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.get(plan.getId().toString(), "c".repeat(64)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
