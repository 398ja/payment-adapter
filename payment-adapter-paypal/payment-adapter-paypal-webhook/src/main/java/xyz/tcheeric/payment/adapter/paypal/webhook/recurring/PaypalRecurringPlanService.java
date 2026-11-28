package xyz.tcheeric.payment.adapter.paypal.webhook.recurring;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalSale;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.PaidPurchase;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.ReversedPurchase;

/**
 * What a PayPal subscription event means for an instalment plan: {@code RecurringPlanService}'s rules, in PayPal's
 * words (ADR 0003).
 *
 * <p>Paid means owed, per instalment: a {@code PAYMENT.SALE.COMPLETED} for a plan's subscription records one purchase
 * row, keyed by the sale, through the same {@link StripePurchaseListener} every other purchase uses. The buyer comes
 * from Imani's plan row, which was written before the subscription existed. PayPal's event only names the
 * subscription, so nothing on it can redirect the vouchers.
 *
 * <p>A fixed term is PayPal's own {@code total_cycles}: PayPal stops after the last instalment and reports
 * {@code BILLING.SUBSCRIPTION.EXPIRED}, so there is no "cancel once N are paid" step to lose, unlike Stripe.
 *
 * <p>Every method is idempotent: PayPal redelivers, and the reconciler replays sales the webhook may have recorded.
 */
@Slf4j
public class PaypalRecurringPlanService {

    /** The ledger event id of a sale: the same whether the webhook or the reconciler records it. */
    public static String eventId(String saleId) {
        return "paypal:sale:" + saleId;
    }

    private final RecurringPlanRepository plans;
    private final StripePurchaseRepository purchases;
    private final StripePurchaseListener listener;
    private final String environment;
    private final boolean livemode;

    public PaypalRecurringPlanService(RecurringPlanRepository plans, StripePurchaseRepository purchases,
            StripePurchaseListener listener, String environment, boolean livemode) {
        this.plans = plans;
        this.purchases = purchases;
        this.listener = listener;
        this.environment = environment;
        this.livemode = livemode;
    }

    /** The PayPal plan a subscription belongs to, if it is one of this deployment's. */
    public Optional<RecurringPlan> planFor(String subscriptionId) {
        return subscriptionId == null ? Optional.empty()
                : plans.findByProcessorAndSubscriptionId(RecurringPlan.PROCESSOR_PAYPAL, subscriptionId);
    }

    /** The buyer approved and PayPal started billing. Issues nothing: the first sale does. */
    public void activated(String subscriptionId) {
        planFor(subscriptionId).ifPresent(plan -> {
            if (plan.getStatus() == Status.PENDING || plan.getStatus() == Status.PAST_DUE) {
                plan.setStatus(Status.ACTIVE);
                save(plan);
            }
        });
    }

    /**
     * An instalment was paid. Records it and updates the plan.
     *
     * @return false when the sale is not a plan's (another integration's subscription), true when it was recorded or
     *         already had been
     * @throws IllegalStateException when the sale does not match the plan, so the event is retried and visible
     */
    public boolean salePaid(PaypalSale sale) {
        Optional<RecurringPlan> found = planFor(sale.subscriptionId());
        if (found.isEmpty()) {
            return false;
        }
        RecurringPlan plan = found.get();
        if (!sale.succeeded() || sale.amountMinor() == null || sale.amountMinor() <= 0) {
            log.info("paypal_plan_sale_not_paid plan={} sale={} state={}", plan.getId(), sale.id(), sale.state());
            return true;
        }
        if (sale.currency() == null || !sale.currency().equalsIgnoreCase(plan.getCurrency())) {
            throw new IllegalStateException("PayPal sale " + sale.id() + " is in " + sale.currency() + " but plan "
                    + plan.getId() + " is in " + plan.getCurrency());
        }
        record(() -> listener.onPurchasePaid(purchase(plan, sale)), sale);
        if (sale.fullyReturned()) {
            // Read by the reconciler after the money went back: recorded, then reversed, never owed.
            record(() -> listener.onPurchaseReversed(new ReversedPurchase(eventId(sale.id()) + ":live-refund",
                    sale.id(), sale.id(), plan.getConnectedAccountId(), sale.amountMinor(), sale.amountMinor(), false)),
                    sale);
        }
        afterInstalment(plan);
        return true;
    }

    /** A refund or reversal on an instalment: the ledger row follows, and the plan is flagged for a person. */
    public void instalmentReturned(String saleId) {
        purchases.findByPaymentIntentId(saleId)
                .filter(row -> row.getRecurringPlanId() != null)
                .flatMap(row -> plans.findById(row.getRecurringPlanId()))
                .ifPresent(plan -> {
                    if (plan.getStatus() != Status.ORPHANED) {
                        log.warn("[alert] paypal_plan_instalment_returned plan={} sale={}", plan.getId(), saleId);
                        plan.setStatus(Status.ATTENTION);
                        save(plan);
                    }
                });
    }

    /** An instalment failed, or PayPal suspended the subscription after repeated failures. Nothing is issued. */
    public void paymentFailed(String subscriptionId) {
        planFor(subscriptionId).ifPresent(plan -> {
            if (plan.getStatus() == Status.ACTIVE || plan.getStatus() == Status.PENDING) {
                plan.setStatus(Status.PAST_DUE);
                save(plan);
            }
        });
    }

    /** Cancelled or expired: completed if every instalment was paid, otherwise cancelled. */
    public void ended(String subscriptionId) {
        planFor(subscriptionId).ifPresent(plan -> {
            if (plan.getStatus() == Status.COMPLETED || plan.getStatus() == Status.ORPHANED
                    || plan.getStatus() == Status.ATTENTION) {
                return;
            }
            plan.setStatus(allPaid(plan) ? Status.COMPLETED : Status.CANCELLED);
            save(plan);
        });
    }

    private void afterInstalment(RecurringPlan plan) {
        RecurringPlan current = plans.findById(plan.getId()).orElse(plan);
        Integer total = current.getTotalInstalments();
        long paid = purchases.countByRecurringPlanId(current.getId());
        boolean endedAlready = current.getStatus() == Status.CANCELLED || current.getStatus() == Status.ORPHANED;
        if (endedAlready || (total != null && paid > total)) {
            log.warn("[alert] paypal_plan_charged_after_end plan={} status={} paid={} total={}",
                    current.getId(), current.getStatus(), paid, total);
            current.setStatus(Status.ATTENTION);
        } else if (total != null && paid >= total && current.getStatus() != Status.ATTENTION) {
            current.setStatus(Status.COMPLETED);
        } else if (current.getStatus() == Status.PENDING || current.getStatus() == Status.PAST_DUE) {
            current.setStatus(Status.ACTIVE);
        }
        save(current);
    }

    private boolean allPaid(RecurringPlan plan) {
        Integer total = plan.getTotalInstalments();
        return total != null && purchases.countByRecurringPlanId(plan.getId()) >= total;
    }

    private PaidPurchase purchase(RecurringPlan plan, PaypalSale sale) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("buyer_pubkey", plan.getBuyerPubkey());
        metadata.put("plan_id", plan.getId().toString());
        if (environment != null) {
            metadata.put(StripeGatewayProperties.ENVIRONMENT_METADATA_KEY, environment);
        }
        // The sale id fills both Stripe-named columns (V28): unique per instalment, so a replay is absorbed.
        return new PaidPurchase(eventId(sale.id()), sale.id(), sale.id(), plan.getConnectedAccountId(),
                sale.amountMinor(), sale.currency(), metadata, livemode, RecurringPlan.PROCESSOR_PAYPAL,
                plan.getStallPubkey());
    }

    /** A ledger write the listener may refuse; refused means "not recorded", so the caller must retry. */
    private interface LedgerWrite {
        void run() throws Exception;
    }

    private static void record(LedgerWrite write, PaypalSale sale) {
        try {
            write.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Purchase listener refused PayPal instalment " + sale.id(), e);
        }
    }

    private void save(RecurringPlan plan) {
        plan.setUpdatedAt(Instant.now());
        plans.save(plan);
    }
}
