package xyz.tcheeric.payment.adapter.test.integration.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.RecordingPurchaseListener;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.InstalmentReconciler;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.InstalmentSource;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource;
import xyz.tcheeric.payment.adapter.stripe.webhook.recurring.RecurringPlanService;
import xyz.tcheeric.payment.adapter.stripe.webhook.recurring.StripeSubscriptionClient;
import xyz.tcheeric.payment.adapter.test.integration.BasePostgresIT;

/**
 * The instalment reconciler end to end: a real database, the real recording listener and the real plan service, with
 * only Stripe faked. This is the test that shows a lost instalment is recovered exactly once, issued to the PLAN's
 * buyer, and that the last instalment of a fixed-term plan ends its subscription.
 */
class InstalmentReconcilerDbIT extends BasePostgresIT {

    private static final String ACCOUNT = "acct_farm";
    private static final String STALL = "a".repeat(64);
    private static final String BUYER = "b".repeat(64);
    private static final Instant NOW = Instant.now();
    private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(2));

    @Autowired private RecurringPlanRepository plans;
    @Autowired private StripePurchaseRepository purchases;
    @Autowired private ConnectedStripeAccountRepository accounts;
    @Autowired private PlatformTransactionManager transactionManager;

    private FakeStripe stripe;
    private InstalmentReconciler reconciler;
    private RecurringPlan plan;

    /** Scripted Stripe: the invoices a subscription has paid, and a record of what the reconciler asked it to do. */
    private static final class FakeStripe implements InstalmentSource, StripeReconcileSource, StripeSubscriptionClient {
        final List<PaidInvoice> invoices = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        boolean refunded;

        @Override public List<PaidInvoice> paidInvoices(String accountId, String subscriptionId, int limit) { return invoices; }
        @Override public PlanSession planSession(String accountId, String sessionId) { return new PlanSession("open", null); }
        @Override public SessionPage completeSessions(String a, Instant f, Instant u, String s, int l) { return new SessionPage(List.of(), false); }
        @Override public PaymentState paymentState(String accountId, String paymentIntentId) {
            return new PaymentState("ch_1", 5000, refunded ? 5000 : 0, false);
        }
        @Override public String paymentIntentForInvoice(String accountId, String invoiceId) { return "pi_" + invoiceId; }
        @Override public void cancelAtPeriodEnd(String accountId, String subscriptionId) { cancelled.add(subscriptionId); }
    }

    @BeforeEach
    void setUp() {
        purchases.deleteAll();
        plans.deleteAll();
        accounts.deleteAll();
        stripe = new FakeStripe();
        RecurringPlanService service = new RecurringPlanService(plans, purchases, stripe, "test");
        RecordingPurchaseListener listener = new RecordingPurchaseListener(purchases, accounts).withEnvironment("test");
        reconciler = InstalmentReconciler.create(plans, purchases, stripe, stripe, service, listener,
                new TransactionTemplate(transactionManager), new PaymentMetrics(),
                new InstalmentReconciler.Settings(true, Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofDays(7), 20, 40),
                "test");
        plan = new RecurringPlan();
        plan.setId(UUID.randomUUID());
        plan.setConnectedAccountId(ACCOUNT);
        plan.setStallPubkey(STALL);
        plan.setBuyerPubkey(BUYER);
        plan.setAmountMinor(5000);
        plan.setCurrency("gbp");
        plan.setSubscriptionId("sub_" + UUID.randomUUID());
        plan.setStatus(Status.ACTIVE);
        plan.setCreatedAt(LONG_AGO);
        plan.setUpdatedAt(LONG_AGO);
    }

    private void paid(String invoiceId) {
        stripe.invoices.add(new InstalmentSource.PaidInvoice(invoiceId, null, 5000, "gbp", LONG_AGO, false));
    }

    // A paid invoice whose webhook was lost becomes a purchase for the PLAN's buyer, keyed by the invoice, linked to the plan.
    @Test
    void recoversALostInstalmentForThePlansBuyer() {
        plans.save(plan);
        paid("in_lost");

        reconciler.runOnce(NOW);

        List<StripePurchase> rows = (List<StripePurchase>) purchases.findAll();
        assertThat(rows).hasSize(1);
        StripePurchase row = rows.get(0);
        assertThat(row.getCheckoutSessionId()).isEqualTo("in_lost");
        assertThat(row.getPaymentIntentId()).isEqualTo("pi_in_lost");
        assertThat(row.getRecipientPubkey()).isEqualTo(BUYER);
        assertThat(row.getRecurringPlanId()).isEqualTo(plan.getId());
        assertThat(row.getAmountMinor()).isEqualTo(5000L);
        assertThat(row.getStatus()).isEqualTo(StripePurchase.Status.OWED);
        assertThat(plans.findById(plan.getId()).orElseThrow().getStatus()).isEqualTo(Status.ACTIVE);
    }

    // Running again (a later window, or a second replica) records nothing more: one invoice, one row.
    @Test
    void runningTwiceRecordsEachInvoiceOnce() {
        plans.save(plan);
        paid("in_1");
        reconciler.runOnce(NOW);

        // a later window: the plan is claimable again
        reconciler.runOnce(NOW.plus(Duration.ofHours(2)));

        assertThat(purchases.count()).isEqualTo(1);
    }

    // An invoice the webhook already recorded is left alone, and several lost ones are all recovered.
    @Test
    void recoversOnlyWhatIsMissing() {
        plans.save(plan);
        paid("in_1");
        paid("in_2");
        paid("in_3");
        StripePurchase existing = new StripePurchase();
        existing.setEventId("evt_webhook_in_1");
        existing.setCheckoutSessionId("in_1");
        existing.setPaymentIntentId("pi_in_1");
        existing.setConnectedAccountId(ACCOUNT);
        existing.setAmountMinor(5000L);
        existing.setCurrency("gbp");
        existing.setStatus(StripePurchase.Status.OWED);
        existing.setPaymentRequestId(UUID.randomUUID().toString());
        existing.setRecurringPlanId(plan.getId());
        existing.setCreatedAt(LONG_AGO);
        existing.setUpdatedAt(LONG_AGO);
        purchases.save(existing);

        reconciler.runOnce(NOW);

        assertThat(purchases.count()).isEqualTo(3);
        assertThat(purchases.findByCheckoutSessionId("in_2")).isPresent();
        assertThat(purchases.findByCheckoutSessionId("in_3")).isPresent();
    }

    // The money rule: an instalment refunded on Stripe is recorded REFUNDED, never OWED, so it can never be issued.
    @Test
    void aRefundedInstalmentIsNeverMadeOwed() {
        plans.save(plan);
        paid("in_refunded");
        stripe.refunded = true;

        reconciler.runOnce(NOW);

        StripePurchase row = purchases.findByCheckoutSessionId("in_refunded").orElseThrow();
        assertThat(row.getStatus()).isNotEqualTo(StripePurchase.Status.OWED);
    }

    // THE over-billing repair: a fixed-term plan that already has every instalment but whose subscription was never
    // ended gets its cancel from the reconciler, once, before the next charge.
    @Test
    void endsTheSubscriptionOfAFixedTermPlanThatReceivedEveryInstalment() {
        plan.setTotalInstalments(2);
        plans.save(plan);
        paid("in_1");
        paid("in_2");

        reconciler.runOnce(NOW);

        assertThat(purchases.count()).isEqualTo(2);
        assertThat(stripe.cancelled).containsExactly(plan.getSubscriptionId());
        RecurringPlan after = plans.findById(plan.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(after.getCancelRequestedAt()).isNotNull();

        // and it does not cancel twice
        reconciler.runOnce(NOW.plus(Duration.ofHours(2)));
        assertThat(stripe.cancelled).hasSize(1);
    }

    // THE repair path proper: every instalment is ALREADY in the ledger (the webhook recorded them) but the webhook that
    // would have ended the subscription was refused or lost. No invoice is missing, so only settle() can catch this, and
    // without it the buyer is charged once more at the next period.
    @Test
    void endsTheSubscriptionEvenWhenNoInvoiceIsMissing() {
        plan.setTotalInstalments(2);
        plans.save(plan);
        for (String invoice : List.of("in_1", "in_2")) {
            StripePurchase recorded = new StripePurchase();
            recorded.setEventId("evt_webhook_" + invoice);
            recorded.setCheckoutSessionId(invoice);
            recorded.setPaymentIntentId("pi_" + invoice);
            recorded.setConnectedAccountId(ACCOUNT);
            recorded.setAmountMinor(5000L);
            recorded.setCurrency("gbp");
            recorded.setStatus(StripePurchase.Status.OWED);
            recorded.setPaymentRequestId(UUID.randomUUID().toString());
            recorded.setRecurringPlanId(plan.getId());
            recorded.setCreatedAt(LONG_AGO);
            recorded.setUpdatedAt(LONG_AGO);
            purchases.save(recorded);
            paid(invoice);
        }

        reconciler.runOnce(NOW);

        assertThat(purchases.count()).as("nothing was missing, so nothing new is recorded").isEqualTo(2);
        assertThat(stripe.cancelled).containsExactly(plan.getSubscriptionId());
        assertThat(plans.findById(plan.getId()).orElseThrow().getStatus()).isEqualTo(Status.COMPLETED);
    }

    // A fixed-term plan one instalment short is NOT ended: the buyer still has a payment to make.
    @Test
    void doesNotEndAPlanThatIsStillOwedAnInstalment() {
        plan.setTotalInstalments(3);
        plans.save(plan);
        paid("in_1");
        paid("in_2");

        reconciler.runOnce(NOW);

        assertThat(stripe.cancelled).isEmpty();
        assertThat(plans.findById(plan.getId()).orElseThrow().getStatus()).isEqualTo(Status.ACTIVE);
    }

    // An invoice paid minutes ago is left to its own webhook.
    @Test
    void leavesAFreshInvoiceToItsWebhook() {
        plans.save(plan);
        stripe.invoices.add(new InstalmentSource.PaidInvoice("in_new", null, 5000, "gbp", NOW.minus(Duration.ofMinutes(2)), false));

        reconciler.runOnce(NOW);

        assertThat(purchases.count()).isZero();
    }

    // The recheck window: a plan checked a moment ago is not read again, so an idle system makes no Stripe calls.
    @Test
    void aRecentlyCheckedPlanIsNotReadAgain() {
        plan.setReconciledAt(NOW.minus(Duration.ofMinutes(5)));
        plans.save(plan);
        paid("in_1");

        reconciler.runOnce(NOW);

        assertThat(purchases.count()).isZero();
    }

    // Claiming a plan is one conditional UPDATE: of two replicas racing for one plan, exactly one wins.
    @Test
    void exactlyOneReplicaClaimsAPlan() throws Exception {
        plans.save(plan);
        int callers = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return plans.claim(plan.getId(), Instant.now(), Instant.now().minus(Duration.ofHours(1)));
                }));
            }
            go.countDown();
            int winners = 0;
            for (java.util.concurrent.Future<Integer> f : results) {
                winners += f.get();
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // An ORPHANED plan (the stall disconnected) is never read: Imani can no longer see that account.
    @Test
    void anOrphanedPlanIsNotReconciled() {
        plan.setStatus(Status.ORPHANED);
        plans.save(plan);
        paid("in_1");

        reconciler.runOnce(NOW);

        assertThat(purchases.count()).isZero();
    }

    // A plan that ended long ago is no longer read, but one that ended recently still is (a late instalment can land).
    @Test
    void anEndedPlanIsReadOnlyWithinTheLookback() {
        plan.setStatus(Status.CANCELLED);
        plan.setUpdatedAt(NOW.minus(Duration.ofDays(30)));
        plans.save(plan);
        paid("in_1");
        reconciler.runOnce(NOW);
        assertThat(purchases.count()).isZero();

        plan.setUpdatedAt(NOW.minus(Duration.ofDays(1)));
        plans.save(plan);
        reconciler.runOnce(NOW);
        assertThat(purchases.count()).isEqualTo(1);
    }
}
