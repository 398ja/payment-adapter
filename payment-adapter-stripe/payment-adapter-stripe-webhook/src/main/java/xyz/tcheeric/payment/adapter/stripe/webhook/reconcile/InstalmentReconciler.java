package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.webhook.StripeWebhookPayload;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.InstalmentSource.PaidInvoice;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.InstalmentSource.PlanSession;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.LostPurchaseRecorder.Outcome;
import xyz.tcheeric.payment.adapter.stripe.webhook.recurring.RecurringPlanException;
import xyz.tcheeric.payment.adapter.stripe.webhook.recurring.RecurringPlanService;

/**
 * Recovers instalments whose webhook was lost (the gap named in the recurring-plans spec).
 *
 * <p>Webhooks are the only way a paid instalment becomes a {@code stripe_purchase} row. Stripe retries a failed one for
 * three days; after that, or after a database restore, a buyer who paid holds nothing and nothing says so. This asks
 * Stripe instead: for each running plan it lists the subscription's paid invoices and records any the ledger lacks.
 *
 * <p>It records through exactly the paths the webhook uses, so the two cannot disagree:
 * <ul>
 * <li>{@link RecurringPlanService#prepare} builds the purchase (the buyer is the plan's, the invoice id is the key);</li>
 * <li>{@link LostPurchaseRecorder#reconcileInstalment} reads the live payment first and records through the same
 * listener as a one-off purchase, so a refunded or disputed instalment is never made mintable;</li>
 * <li>{@link RecurringPlanService#recorded} then updates the plan.</li>
 * </ul>
 *
 * <p><b>Also repairs the dangerous case.</b> If the last instalment of a fixed-term plan was recorded but its
 * subscription was never ended, the buyer is charged once too many at the next period. {@link
 * RecurringPlanService#settle} ends it, for every running plan, whether or not any invoice was missing.
 *
 * <p><b>Two replicas never work one plan.</b> A plan is claimed with one conditional UPDATE; the replica that loses the
 * claim skips it. The claim is also the rotation order, so a bounded run reaches every plan over time, and the recheck
 * window means an idle system makes no Stripe calls.
 *
 * <p>Not here: a plan with no subscription and no Checkout Session (nothing to read), or an {@code ORPHANED} plan
 * (Imani can no longer see that account).
 */
@Slf4j
public class InstalmentReconciler {

    private static final int INVOICE_PAGE = 100;

    private final RecurringPlanRepository plans;
    private final StripePurchaseRepository purchases;
    private final InstalmentSource stripe;
    private final RecurringPlanService service;
    private final LostPurchaseRecorder recorder;
    private final Settings settings;
    private final String environment;

    /**
     * Builds the reconciler from its public collaborators. The configuration uses this, and so does the integration
     * test, which needs the real recorder over a real database without reaching into package-private types.
     */
    public static InstalmentReconciler create(RecurringPlanRepository plans, StripePurchaseRepository purchases,
            InstalmentSource stripe, StripeReconcileSource payments, RecurringPlanService service,
            xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener listener,
            org.springframework.transaction.support.TransactionOperations transactions,
            xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics metrics, Settings settings,
            String environment) {
        return new InstalmentReconciler(plans, purchases, stripe, service,
                new LostPurchaseRecorder(payments, purchases, listener, transactions, metrics), settings, environment);
    }

    /** Runs one pass now. For tests and operators; the schedule calls {@link #tick()}. */
    public void runOnce(Instant now) {
        run(now);
    }

    InstalmentReconciler(RecurringPlanRepository plans, StripePurchaseRepository purchases, InstalmentSource stripe,
            RecurringPlanService service, LostPurchaseRecorder recorder, Settings settings, String environment) {
        this.plans = plans;
        this.purchases = purchases;
        this.stripe = stripe;
        this.service = service;
        this.recorder = recorder;
        this.settings = settings;
        this.environment = environment == null || environment.isBlank() ? null : environment.trim().toLowerCase();
    }

    /**
     * @param enabled whether the scheduled job runs
     * @param grace an invoice paid more recently than this is left to its webhook
     * @param recheck a plan checked within this window is skipped; also the claim window between replicas
     * @param endedLookback how long after a plan ends it is still read, for an instalment paid late
     * @param batchSize plans claimed per run
     * @param maxStripeCallsPerRun every Stripe read counts, shared with checkout's rate limit
     */
    public record Settings(boolean enabled, Duration grace, Duration recheck, Duration endedLookback, int batchSize,
            int maxStripeCallsPerRun) {
        public Settings {
            batchSize = Math.max(1, batchSize);
            maxStripeCallsPerRun = Math.max(1, maxStripeCallsPerRun);
        }
    }

    @Scheduled(fixedDelayString = "${stripe.recurring.reconcile.interval:PT10M}",
            initialDelayString = "${stripe.recurring.reconcile.initial-delay:PT3M}")
    void tick() {
        if (!settings.enabled()) {
            return;
        }
        try {
            run(Instant.now());
        } catch (RuntimeException e) {
            // One bad run must not stop the schedule: the next run is the recovery.
            log.warn("instalment_reconcile run_failed", e);
        }
    }

    /** One run. Visible for tests. */
    void run(Instant now) {
        ReconcileBudget budget = new ReconcileBudget(settings.maxStripeCallsPerRun());
        List<RecurringPlan> candidates = plans.findReconcilable(
                EnumSet.of(Status.PENDING, Status.ACTIVE, Status.PAST_DUE, Status.ATTENTION),
                EnumSet.of(Status.CANCELLED, Status.COMPLETED),
                now.minus(settings.endedLookback()), now.minus(settings.grace()), now.minus(settings.recheck()),
                PageRequest.of(0, settings.batchSize()));
        for (RecurringPlan candidate : candidates) {
            if (budget.exhausted()) {
                log.warn("instalment_reconcile budget spent; remaining plans continue next run");
                break;
            }
            // The claim IS the lease: lose it and another replica has this plan for the window.
            if (plans.claim(candidate.getId(), now, now.minus(settings.recheck())) != 1) {
                continue;
            }
            try {
                reconcilePlan(candidate, now, budget);
            } catch (StripeReconcileException e) {
                if (e.isRateLimited()) {
                    budget.rateLimited();
                }
                log.warn("instalment_reconcile plan {} stopped, retried after the recheck window: {}",
                        candidate.getId(), e.getMessage());
            } catch (RuntimeException e) {
                // One plan's failure must not hide the others. It is claimed, so it is retried next window.
                log.warn("instalment_reconcile plan {} failed, retried after the recheck window",
                        candidate.getId(), e);
            }
        }
    }

    private void reconcilePlan(RecurringPlan candidate, Instant now, ReconcileBudget budget) {
        RecurringPlan plan = candidate;
        if (plan.getSubscriptionId() == null) {
            plan = resolveSubscription(plan, budget);
            if (plan == null) {
                return;
            }
        }
        if (!budget.spend()) {
            return;
        }
        List<PaidInvoice> invoices = stripe.paidInvoices(plan.getConnectedAccountId(), plan.getSubscriptionId(), INVOICE_PAGE);
        for (PaidInvoice invoice : invoices) {
            if (!recoverInvoice(plan, invoice, now, budget)) {
                return;
            }
        }
        // Whether or not anything was missing: end a fixed-term plan that has had every instalment and is still running.
        try {
            service.settle(plan.getId());
        } catch (RecurringPlanException e) {
            log.warn("[alert] instalment_reconcile could not end plan {} after its last instalment: {}",
                    plan.getId(), e.getMessage());
        }
    }

    /**
     * A PENDING plan has no subscription yet. If its Checkout Session completed and the webhook saying so was lost,
     * link the subscription now; if the session expired, close the plan. Returns the plan to continue with, or null.
     */
    private RecurringPlan resolveSubscription(RecurringPlan plan, ReconcileBudget budget) {
        if (plan.getCheckoutSessionId() == null || !budget.spend()) {
            return null;
        }
        PlanSession session = stripe.planSession(plan.getConnectedAccountId(), plan.getCheckoutSessionId());
        StripeWebhookPayload payload = StripeWebhookPayload.builder()
                .eventId("reconcile:" + plan.getCheckoutSessionId())
                .eventType("complete".equals(session.status()) ? "checkout.session.completed" : "checkout.session.expired")
                .checkoutSessionId(plan.getCheckoutSessionId())
                .subscriptionId(session.subscriptionId())
                .connectedAccountId(plan.getConnectedAccountId())
                .metadata(metadata(plan))
                .build();
        if ("complete".equals(session.status()) && session.subscriptionId() != null) {
            service.checkoutCompleted(payload);
            log.warn("[alert] instalment_reconcile linked plan {} to subscription {}: its session webhook was lost",
                    plan.getId(), session.subscriptionId());
            return plans.findById(plan.getId()).filter(p -> p.getSubscriptionId() != null).orElse(null);
        }
        if ("expired".equals(session.status())) {
            service.checkoutExpired(payload);
        }
        return null;
    }

    /** @return false when the budget ran out, so the caller stops this plan and keeps its place */
    private boolean recoverInvoice(RecurringPlan plan, PaidInvoice invoice, Instant now, ReconcileBudget budget) {
        if (invoice.amountPaid() <= 0 || invoice.paidAt().isAfter(now.minus(settings.grace()))) {
            return true;
        }
        if (purchases.findByCheckoutSessionId(invoice.id()).isPresent()) {
            return true;
        }
        StripeWebhookPayload payload = StripeWebhookPayload.builder()
                .eventId("reconcile:" + invoice.id())
                .eventType("invoice.paid")
                .checkoutSessionId(invoice.id())
                .subscriptionId(plan.getSubscriptionId())
                .paymentIntentId(invoice.paymentIntentId())
                .amountTotal(Math.toIntExact(invoice.amountPaid()))
                .currency(invoice.currency())
                .livemode(invoice.livemode())
                .connectedAccountId(plan.getConnectedAccountId())
                .metadata(metadata(plan))
                .build();
        RecurringPlanService.Instalment instalment;
        try {
            instalment = service.prepare(payload);
        } catch (RecurringPlanException e) {
            // The same refusals the webhook makes (wrong account, wrong currency, no payment intent). Loud, and the
            // rest of the plan's invoices are still read.
            log.warn("[alert] instalment_reconcile could not match paid invoice {} of plan {}: {}",
                    invoice.id(), plan.getId(), e.getMessage());
            return true;
        }
        if (instalment == null) {
            return true;
        }
        Outcome outcome = recorder.reconcileInstalment(plan.getConnectedAccountId(), instalment.purchase(), budget);
        if (outcome == Outcome.DEFERRED) {
            return false;
        }
        // A race the webhook won already updated the plan; doing it again is harmless and covers the webhook that
        // recorded the purchase but died before touching the plan.
        service.recorded(instalment.plan(), payload);
        return true;
    }

    /** What the plan service needs to know the event is ours: the plan, and this deployment's environment stamp. */
    private Map<String, String> metadata(RecurringPlan plan) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(RecurringPlanService.PLAN_ID, plan.getId().toString());
        // A plan row exists only because this deployment created it, so it is ours to stamp. Without the stamp the
        // listener would park the row as an unstamped session when an environment is configured.
        if (environment != null) {
            metadata.put(StripeGatewayProperties.ENVIRONMENT_METADATA_KEY, environment);
        }
        return metadata;
    }
}
