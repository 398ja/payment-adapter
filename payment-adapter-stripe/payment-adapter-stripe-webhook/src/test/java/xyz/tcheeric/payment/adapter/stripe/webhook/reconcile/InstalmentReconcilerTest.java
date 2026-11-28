package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.webhook.StripeWebhookPayload;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.InstalmentSource.PaidInvoice;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.InstalmentSource.PlanSession;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.LostPurchaseRecorder.Outcome;
import xyz.tcheeric.payment.adapter.stripe.webhook.recurring.RecurringPlanException;
import xyz.tcheeric.payment.adapter.stripe.webhook.recurring.RecurringPlanService;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.PaidPurchase;

/**
 * The instalment reconciler's decisions: which invoices it records, which it leaves, and that it never does anything a
 * webhook would not. The recording itself (live payment first, through the listener) is the one-off reconciler's code
 * and is covered where it lives; here it is a mock.
 */
class InstalmentReconcilerTest {

    private static final String ACCOUNT = "acct_farm";
    private static final Instant NOW = Instant.parse("2026-11-28T12:00:00Z");
    private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(2));

    private RecurringPlanRepository plans;
    private StripePurchaseRepository purchases;
    private InstalmentSource stripe;
    private RecurringPlanService service;
    private LostPurchaseRecorder recorder;
    private InstalmentReconciler reconciler;
    private RecurringPlan plan;

    @BeforeEach
    void setUp() {
        plans = mock(RecurringPlanRepository.class);
        purchases = mock(StripePurchaseRepository.class);
        stripe = mock(InstalmentSource.class);
        service = mock(RecurringPlanService.class);
        recorder = mock(LostPurchaseRecorder.class);
        reconciler = new InstalmentReconciler(plans, purchases, stripe, service, recorder,
                new InstalmentReconciler.Settings(true, Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofDays(7), 20, 40),
                "staging");

        plan = new RecurringPlan();
        plan.setId(UUID.randomUUID());
        plan.setConnectedAccountId(ACCOUNT);
        plan.setSubscriptionId("sub_1");
        plan.setStatus(Status.ACTIVE);
        plan.setCurrency("gbp");
        plan.setAmountMinor(5000);
        plan.setCreatedAt(LONG_AGO);
        lenient().when(plans.findReconcilable(any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(List.of(plan));
        lenient().when(plans.claim(eq(plan.getId()), any(), any())).thenReturn(1);
        lenient().when(plans.findById(plan.getId())).thenReturn(Optional.of(plan));
        lenient().when(purchases.findByCheckoutSessionId(anyString())).thenReturn(Optional.empty());
    }

    private static PaidInvoice invoice(String id, long paid, Instant paidAt) {
        return new PaidInvoice(id, null, paid, "gbp", paidAt, false);
    }

    private RecurringPlanService.Instalment instalment(String invoiceId) {
        return new RecurringPlanService.Instalment(plan,
                new PaidPurchase("reconcile:" + invoiceId, invoiceId, "pi_" + invoiceId, ACCOUNT, 5000L, "gbp", java.util.Map.of(), false));
    }

    // A paid invoice with no ledger row is recorded through the plan service and the shared recorder, then the plan is updated.
    @Test
    void recordsAPaidInvoiceTheLedgerLacks() {
        when(stripe.paidInvoices(ACCOUNT, "sub_1", 100)).thenReturn(List.of(invoice("in_1", 5000, LONG_AGO)));
        var i = instalment("in_1");
        when(service.prepare(any())).thenReturn(i);
        when(recorder.reconcileInstalment(eq(ACCOUNT), eq(i.purchase()), any())).thenReturn(Outcome.RECORDED_OWED);

        reconciler.run(NOW);

        InOrder order = inOrder(service, recorder);
        order.verify(service).prepare(any());
        order.verify(recorder).reconcileInstalment(eq(ACCOUNT), eq(i.purchase()), any());
        order.verify(service).recorded(eq(plan), any());
    }

    // The payload it builds is a plain invoice.paid for the plan, stamped with this deployment's environment.
    @Test
    void buildsTheSamePayloadAWebhookWould() {
        when(stripe.paidInvoices(ACCOUNT, "sub_1", 100)).thenReturn(List.of(invoice("in_1", 5000, LONG_AGO)));
        when(service.prepare(any())).thenReturn(instalment("in_1"));
        when(recorder.reconcileInstalment(any(), any(), any())).thenReturn(Outcome.RECORDED_OWED);

        reconciler.run(NOW);

        ArgumentCaptor<StripeWebhookPayload> sent = ArgumentCaptor.forClass(StripeWebhookPayload.class);
        verify(service).prepare(sent.capture());
        StripeWebhookPayload p = sent.getValue();
        assertThat(p.getEventType()).isEqualTo("invoice.paid");
        assertThat(p.getEventId()).isEqualTo("reconcile:in_1");
        assertThat(p.getCheckoutSessionId()).isEqualTo("in_1");
        assertThat(p.getSubscriptionId()).isEqualTo("sub_1");
        assertThat(p.getAmountTotal()).isEqualTo(5000);
        assertThat(p.getConnectedAccountId()).isEqualTo(ACCOUNT);
        assertThat(p.getMetadata()).containsEntry("plan_id", plan.getId().toString())
                .containsEntry(StripeGatewayProperties.ENVIRONMENT_METADATA_KEY, "staging");
    }

    // An invoice already in the ledger costs no Stripe read and no recording.
    @Test
    void leavesAnInvoiceAlreadyRecorded() {
        when(stripe.paidInvoices(ACCOUNT, "sub_1", 100)).thenReturn(List.of(invoice("in_1", 5000, LONG_AGO)));
        when(purchases.findByCheckoutSessionId("in_1")).thenReturn(Optional.of(new StripePurchase()));

        reconciler.run(NOW);

        verify(service, never()).prepare(any());
        verify(recorder, never()).reconcileInstalment(any(), any(), any());
    }

    // An invoice paid minutes ago belongs to its webhook, which is probably still on its way.
    @Test
    void leavesAnInvoicePaidWithinTheGraceToItsWebhook() {
        when(stripe.paidInvoices(ACCOUNT, "sub_1", 100))
                .thenReturn(List.of(invoice("in_new", 5000, NOW.minus(Duration.ofMinutes(3)))));

        reconciler.run(NOW);

        verify(service, never()).prepare(any());
    }

    // A zero-amount invoice (a free period) paid nothing and so issues nothing.
    @Test
    void leavesAZeroAmountInvoice() {
        when(stripe.paidInvoices(ACCOUNT, "sub_1", 100)).thenReturn(List.of(invoice("in_0", 0, LONG_AGO)));

        reconciler.run(NOW);

        verify(service, never()).prepare(any());
    }

    // A plan service refusal (wrong currency, no payment intent) is logged and the plan's other invoices are still read.
    @Test
    void aRefusedInvoiceDoesNotStopTheRestOfThePlan() {
        when(stripe.paidInvoices(ACCOUNT, "sub_1", 100))
                .thenReturn(List.of(invoice("in_bad", 5000, LONG_AGO), invoice("in_ok", 5000, LONG_AGO)));
        when(service.prepare(argThat("in_bad"))).thenThrow(new RecurringPlanException("currency differs"));
        var ok = instalment("in_ok");
        when(service.prepare(argThat("in_ok"))).thenReturn(ok);
        when(recorder.reconcileInstalment(any(), eq(ok.purchase()), any())).thenReturn(Outcome.RECORDED_OWED);

        reconciler.run(NOW);

        verify(recorder).reconcileInstalment(any(), eq(ok.purchase()), any());
    }

    private static StripeWebhookPayload argThat(String invoiceId) {
        return org.mockito.ArgumentMatchers.argThat(p -> p != null && invoiceId.equals(p.getCheckoutSessionId()));
    }

    // When the call budget runs out mid-plan, the run stops that plan and does not update it as if it were done.
    @Test
    void stopsAPlanWhenTheBudgetRunsOut() {
        when(stripe.paidInvoices(ACCOUNT, "sub_1", 100)).thenReturn(List.of(invoice("in_1", 5000, LONG_AGO)));
        var i = instalment("in_1");
        when(service.prepare(any())).thenReturn(i);
        when(recorder.reconcileInstalment(any(), any(), any())).thenReturn(Outcome.DEFERRED);

        reconciler.run(NOW);

        verify(service, never()).recorded(any(), any());
        verify(service, never()).settle(any());
    }

    // A replica that loses the claim skips the plan: it makes no Stripe call for it.
    @Test
    void aPlanClaimedByAnotherReplicaIsSkipped() {
        when(plans.claim(eq(plan.getId()), any(), any())).thenReturn(0);

        reconciler.run(NOW);

        verify(stripe, never()).paidInvoices(anyString(), anyString(), anyInt());
        verify(service, never()).settle(any());
    }

    // After reading the invoices the plan is settled, so a fixed-term plan whose last cancel was lost gets ended.
    @Test
    void settlesTheRunningPlanAfterReadingItsInvoices() {
        when(stripe.paidInvoices(ACCOUNT, "sub_1", 100)).thenReturn(List.of());

        reconciler.run(NOW);

        verify(service).settle(plan.getId());
    }

    // A failure ending the plan is logged for a person and does not abort the run.
    @Test
    void aFailedSettleDoesNotAbortTheRun() {
        when(stripe.paidInvoices(ACCOUNT, "sub_1", 100)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RecurringPlanException("stripe down")).when(service).settle(any());

        reconciler.run(NOW);

        verify(service).settle(plan.getId());
    }

    // One plan failing does not hide the others in the batch.
    @Test
    void aFailingPlanDoesNotStopTheNext() {
        RecurringPlan second = new RecurringPlan();
        second.setId(UUID.randomUUID());
        second.setConnectedAccountId(ACCOUNT);
        second.setSubscriptionId("sub_2");
        second.setStatus(Status.ACTIVE);
        second.setCreatedAt(LONG_AGO);
        when(plans.findReconcilable(any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(List.of(plan, second));
        when(plans.claim(eq(second.getId()), any(), any())).thenReturn(1);
        when(stripe.paidInvoices(ACCOUNT, "sub_1", 100)).thenThrow(new IllegalStateException("boom"));
        when(stripe.paidInvoices(ACCOUNT, "sub_2", 100)).thenReturn(List.of());

        reconciler.run(NOW);

        verify(service).settle(second.getId());
    }

    // A 429 from Stripe ends the whole run: the remaining plans are not read, and are retried next run.
    @Test
    void aRateLimitEndsTheRun() {
        RecurringPlan second = new RecurringPlan();
        second.setId(UUID.randomUUID());
        second.setConnectedAccountId(ACCOUNT);
        second.setSubscriptionId("sub_2");
        second.setStatus(Status.ACTIVE);
        second.setCreatedAt(LONG_AGO);
        when(plans.findReconcilable(any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(List.of(plan, second));
        lenient().when(plans.claim(eq(second.getId()), any(), any())).thenReturn(1);
        when(stripe.paidInvoices(ACCOUNT, "sub_1", 100))
                .thenThrow(new StripeReconcileException("429", new RuntimeException(), true));

        reconciler.run(NOW);

        verify(stripe, never()).paidInvoices(eq(ACCOUNT), eq("sub_2"), anyInt());
    }

    // A PENDING plan whose session completed (its webhook lost) is linked to the subscription, then read as normal.
    @Test
    void linksAPendingPlanWhoseSessionCompleted() {
        plan.setStatus(Status.PENDING);
        plan.setSubscriptionId(null);
        plan.setCheckoutSessionId("cs_1");
        when(stripe.planSession(ACCOUNT, "cs_1")).thenReturn(new PlanSession("complete", "sub_9"));
        // after the link, the plan reloads with its subscription
        org.mockito.Mockito.doAnswer(inv -> {
            plan.setSubscriptionId("sub_9");
            plan.setStatus(Status.ACTIVE);
            return null;
        }).when(service).checkoutCompleted(any());
        when(stripe.paidInvoices(ACCOUNT, "sub_9", 100)).thenReturn(List.of());

        reconciler.run(NOW);

        ArgumentCaptor<StripeWebhookPayload> sent = ArgumentCaptor.forClass(StripeWebhookPayload.class);
        verify(service).checkoutCompleted(sent.capture());
        assertThat(sent.getValue().getSubscriptionId()).isEqualTo("sub_9");
        assertThat(sent.getValue().getMetadata()).containsEntry("plan_id", plan.getId().toString());
        verify(stripe).paidInvoices(ACCOUNT, "sub_9", 100);
    }

    // A PENDING plan whose session expired is closed, and nothing is listed for it.
    @Test
    void closesAPendingPlanWhoseSessionExpired() {
        plan.setStatus(Status.PENDING);
        plan.setSubscriptionId(null);
        plan.setCheckoutSessionId("cs_1");
        when(stripe.planSession(ACCOUNT, "cs_1")).thenReturn(new PlanSession("expired", null));

        reconciler.run(NOW);

        verify(service).checkoutExpired(any());
        verify(stripe, never()).paidInvoices(anyString(), anyString(), anyInt());
    }

    // A PENDING plan whose session is still open (the buyer is paying) is left alone.
    @Test
    void leavesAPendingPlanWhoseSessionIsStillOpen() {
        plan.setStatus(Status.PENDING);
        plan.setSubscriptionId(null);
        plan.setCheckoutSessionId("cs_1");
        when(stripe.planSession(ACCOUNT, "cs_1")).thenReturn(new PlanSession("open", null));

        reconciler.run(NOW);

        verify(service, never()).checkoutCompleted(any());
        verify(service, never()).checkoutExpired(any());
        verify(stripe, never()).paidInvoices(anyString(), anyString(), anyInt());
    }

    // A plan with neither a subscription nor a session has nothing to read.
    @Test
    void aPlanWithNothingToReadIsSkipped() {
        plan.setStatus(Status.PENDING);
        plan.setSubscriptionId(null);
        plan.setCheckoutSessionId(null);

        reconciler.run(NOW);

        verify(stripe, never()).planSession(anyString(), anyString());
        verify(stripe, never()).paidInvoices(anyString(), anyString(), anyInt());
    }

    // With the job disabled the scheduled tick does nothing at all.
    @Test
    void aDisabledJobDoesNothing() {
        InstalmentReconciler off = new InstalmentReconciler(plans, purchases, stripe, service, recorder,
                new InstalmentReconciler.Settings(false, Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofDays(7), 20, 40), null);

        off.tick();

        verify(plans, never()).findReconcilable(any(), any(), any(), any(), any(), any(Pageable.class));
    }

    // The budget bounds Stripe calls: with room for one call, only the first plan is read.
    @Test
    void theBudgetBoundsTheCallsPerRun() {
        InstalmentReconciler tight = new InstalmentReconciler(plans, purchases, stripe, service, recorder,
                new InstalmentReconciler.Settings(true, Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofDays(7), 20, 1), null);
        RecurringPlan second = new RecurringPlan();
        second.setId(UUID.randomUUID());
        second.setConnectedAccountId(ACCOUNT);
        second.setSubscriptionId("sub_2");
        second.setStatus(Status.ACTIVE);
        second.setCreatedAt(LONG_AGO);
        when(plans.findReconcilable(any(), any(), any(), any(), any(), any(Pageable.class))).thenReturn(List.of(plan, second));
        lenient().when(plans.claim(eq(second.getId()), any(), any())).thenReturn(1);
        when(stripe.paidInvoices(anyString(), anyString(), anyInt())).thenReturn(List.of());

        tight.run(NOW);

        verify(stripe, times(1)).paidInvoices(anyString(), anyString(), anyInt());
    }
}
