package xyz.tcheeric.payment.adapter.paypal.webhook.recurring;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSale;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSubscription;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalSubscriptionsClient;

/**
 * Finds PayPal instalments whose webhook was lost: Stripe's {@code InstalmentReconciler}, for PayPal (ADR 0003).
 *
 * <p>For each running plan (and one ended within the lookback, for a late sale) it lists the subscription's
 * transactions and records any completed sale the ledger lacks, through {@link PaypalRecurringPlanService#salePaid},
 * the webhook's own path. A plan still awaiting approval after {@code abandon-after} has its subscription cancelled
 * and is closed, so a forgotten approval link can never start charging later. A plan whose subscription PayPal reports
 * ended is closed as the lost {@code BILLING.SUBSCRIPTION.*} event would have closed it.
 *
 * <p>Plans are claimed with the shared one-statement lease ({@link RecurringPlanRepository#claim}), so two replicas
 * never work the same plan, and a 429 ends the run.
 */
@Slf4j
public class PaypalInstalmentReconciler {

    private static final EnumSet<Status> ENDED = EnumSet.of(Status.COMPLETED, Status.CANCELLED);

    private final RecurringPlanRepository plans;
    private final StripePurchaseRepository purchases;
    private final PaypalSubscriptionsClient paypal;
    private final PaypalRecurringPlanService service;
    private final PaypalRecurringProperties properties;
    private final Clock clock;

    public PaypalInstalmentReconciler(RecurringPlanRepository plans, StripePurchaseRepository purchases,
            PaypalSubscriptionsClient paypal, PaypalRecurringPlanService service, PaypalRecurringProperties properties,
            Clock clock) {
        this.plans = plans;
        this.purchases = purchases;
        this.paypal = paypal;
        this.service = service;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paypal.recurring.reconcile-interval:PT10M}",
            initialDelayString = "${paypal.recurring.reconcile-interval:PT10M}")
    public void scheduled() {
        run();
    }

    /** @return how many plans were read */
    public int run() {
        Instant now = clock.instant();
        List<RecurringPlan> candidates = plans.findReconcilableFor(RecurringPlan.PROCESSOR_PAYPAL, RecurringPlan.LIVE,
                ENDED, now.minus(properties.getReconcileLookback()), now.minus(properties.getReconcileGrace()),
                now.minus(properties.getReconcileRecheck()), PageRequest.of(0, Math.max(1, properties.getReconcileBatchSize())));
        int read = 0;
        for (RecurringPlan candidate : candidates) {
            if (plans.claim(candidate.getId(), now, now.minus(properties.getReconcileRecheck())) != 1) {
                continue;
            }
            try {
                reconcile(candidate, now);
                read++;
            } catch (PaypalApiException e) {
                if (e.isRateLimited()) {
                    log.warn("paypal_instalment_reconcile_rate_limited after {} plans", read);
                    break;
                }
                log.warn("paypal_instalment_reconcile_failed plan={}: {}", candidate.getId(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("paypal_instalment_reconcile_failed plan={}: {}", candidate.getId(), e.getMessage());
            }
        }
        return read;
    }

    void reconcile(RecurringPlan plan, Instant now) {
        if (plan.getSubscriptionId() == null) {
            return;
        }
        if (plan.getStatus() == Status.PENDING) {
            reconcilePending(plan, now);
            return;
        }
        Instant from = now.minus(properties.getReconcileLookback());
        Instant settled = now.minus(properties.getReconcileGrace());
        for (PaypalSale sale : paypal.transactions(plan.getConnectedAccountId(), plan.getSubscriptionId(), from, now)) {
            boolean young = sale.paidAt() != null && sale.paidAt().isAfter(settled);
            if (sale.succeeded() && !young && purchases.findByEventId(PaypalRecurringPlanService.eventId(sale.id())).isEmpty()) {
                log.warn("[alert] paypal_instalment_recovered plan={} sale={}", plan.getId(), sale.id());
                service.salePaid(sale);
            }
        }
        PaypalSubscription subscription = paypal.getSubscription(plan.getConnectedAccountId(), plan.getSubscriptionId());
        if (subscription.ended()) {
            service.ended(plan.getSubscriptionId());
        }
    }

    private void reconcilePending(RecurringPlan plan, Instant now) {
        PaypalSubscription subscription = paypal.getSubscription(plan.getConnectedAccountId(), plan.getSubscriptionId());
        if (subscription.started() && !subscription.ended()) {
            service.activated(plan.getSubscriptionId());
            return;
        }
        if (subscription.ended()) {
            service.ended(plan.getSubscriptionId());
            return;
        }
        if (plan.getCreatedAt().isBefore(now.minus(properties.getAbandonAfter()))) {
            paypal.cancelSubscription(plan.getConnectedAccountId(), plan.getSubscriptionId(), "Never approved");
            service.ended(plan.getSubscriptionId());
            log.info("paypal_plan_abandoned plan={}", plan.getId());
        }
    }
}
