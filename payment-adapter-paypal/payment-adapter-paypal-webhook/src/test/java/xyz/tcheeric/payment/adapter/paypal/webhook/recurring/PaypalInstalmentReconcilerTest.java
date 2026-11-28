package xyz.tcheeric.payment.adapter.paypal.webhook.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSale;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSubscription;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalSubscriptionsClient;

class PaypalInstalmentReconcilerTest {

    static final Instant NOW = Instant.parse("2027-03-01T12:00:00Z");
    static final String SUB = PaypalRecurringPlanServiceTest.SUBSCRIPTION;
    static final String MERCHANT = PaypalRecurringPlanServiceTest.MERCHANT;

    RecurringPlanRepository plans;
    StripePurchaseRepository purchases;
    PaypalSubscriptionsClient paypal;
    PaypalRecurringPlanService service;
    PaypalInstalmentReconciler reconciler;
    RecurringPlan plan;

    @BeforeEach
    void setUp() {
        plans = mock(RecurringPlanRepository.class);
        purchases = mock(StripePurchaseRepository.class);
        paypal = mock(PaypalSubscriptionsClient.class);
        service = mock(PaypalRecurringPlanService.class);
        reconciler = new PaypalInstalmentReconciler(plans, purchases, paypal, service, new PaypalRecurringProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        plan = PaypalRecurringPlanServiceTest.plan(Status.ACTIVE, 6);
        plan.setCreatedAt(NOW.minus(Duration.ofDays(40)));
        lenient().when(plans.findReconcilableFor(eq(RecurringPlan.PROCESSOR_PAYPAL), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(List.of(plan));
        lenient().when(plans.claim(eq(plan.getId()), any(), any())).thenReturn(1);
        lenient().when(paypal.getSubscription(MERCHANT, SUB))
                .thenReturn(new PaypalSubscription(SUB, "ACTIVE", null, "P-1", null));
    }

    // A completed sale the ledger lacks is recorded through the webhook's own path.
    @Test
    void aMissedInstalmentIsRecovered() {
        PaypalSale missed = new PaypalSale("SALE-1", "completed", 5000L, "gbp", SUB, NOW.minus(Duration.ofDays(2)));
        when(paypal.transactions(eq(MERCHANT), eq(SUB), any(), any())).thenReturn(List.of(missed));
        when(purchases.findByEventId("paypal:sale:SALE-1")).thenReturn(Optional.empty());

        assertThat(reconciler.run()).isEqualTo(1);

        verify(service).salePaid(missed);
    }

    // A sale already recorded, or one still inside the webhook's grace, is left alone.
    @Test
    void recordedAndYoungSalesAreLeftAlone() {
        PaypalSale known = new PaypalSale("SALE-1", "completed", 5000L, "gbp", SUB, NOW.minus(Duration.ofDays(2)));
        PaypalSale young = new PaypalSale("SALE-2", "completed", 5000L, "gbp", SUB, NOW.minus(Duration.ofMinutes(1)));
        when(paypal.transactions(eq(MERCHANT), eq(SUB), any(), any())).thenReturn(List.of(known, young));
        when(purchases.findByEventId("paypal:sale:SALE-1")).thenReturn(Optional.of(new StripePurchase()));
        lenient().when(purchases.findByEventId("paypal:sale:SALE-2")).thenReturn(Optional.empty());

        reconciler.run();

        verify(service, never()).salePaid(any());
    }

    // A plan another replica claimed is skipped.
    @Test
    void aPlanClaimedElsewhereIsSkipped() {
        when(plans.claim(eq(plan.getId()), any(), any())).thenReturn(0);

        assertThat(reconciler.run()).isZero();

        verify(paypal, never()).transactions(anyString(), anyString(), any(), any());
    }

    // A subscription PayPal reports ended closes the plan, as its lost event would have.
    @Test
    void anEndedSubscriptionClosesThePlan() {
        when(paypal.transactions(eq(MERCHANT), eq(SUB), any(), any())).thenReturn(List.of());
        when(paypal.getSubscription(MERCHANT, SUB)).thenReturn(new PaypalSubscription(SUB, "EXPIRED", null, "P-1", null));

        reconciler.run();

        verify(service).ended(SUB);
    }

    // A plan never approved within abandon-after has its subscription cancelled, so the link cannot start it later.
    @Test
    void anUnapprovedPlanIsAbandoned() {
        plan.setStatus(Status.PENDING);
        when(paypal.getSubscription(MERCHANT, SUB))
                .thenReturn(new PaypalSubscription(SUB, "APPROVAL_PENDING", null, "P-1", "https://approve"));

        reconciler.run();

        verify(paypal).cancelSubscription(eq(MERCHANT), eq(SUB), anyString());
        verify(service).ended(SUB);
    }

    // A pending plan whose activation event was lost is activated.
    @Test
    void aLostActivationIsRepaired() {
        plan.setStatus(Status.PENDING);

        reconciler.run();

        verify(service).activated(SUB);
    }

    // A rate limit ends the run instead of hammering PayPal.
    @Test
    void aRateLimitEndsTheRun() {
        RecurringPlan second = PaypalRecurringPlanServiceTest.plan(Status.ACTIVE, 6);
        second.setSubscriptionId("I-SECOND");
        when(plans.findReconcilableFor(eq(RecurringPlan.PROCESSOR_PAYPAL), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(List.of(plan, second));
        when(paypal.transactions(eq(MERCHANT), eq(SUB), any(), any()))
                .thenThrow(new PaypalApiException("slow down", 429, "RATE_LIMIT_REACHED", null, null));

        reconciler.run();

        verify(plans, never()).claim(eq(second.getId()), any(), any());
    }
}
