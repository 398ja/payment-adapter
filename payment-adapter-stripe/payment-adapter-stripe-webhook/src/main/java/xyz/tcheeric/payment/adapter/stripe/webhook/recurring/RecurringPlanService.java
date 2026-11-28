package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.RecurringPlan.Status;
import xyz.tcheeric.payment.adapter.core.model.repository.RecurringPlanRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.webhook.StripeWebhookPayload;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.RecordingPurchaseListener;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.PaidPurchase;

/**
 * What a Stripe subscription event means for an instalment plan.
 *
 * <p>The rule that shapes all of it is "paid means owed, per instalment": vouchers for an instalment are issued when
 * (and only when) that instalment's invoice is paid. A plan that stops after three of six payments has issued three
 * instalments of vouchers. Nothing is ever issued ahead of payment, so there is no credit to recover.
 *
 * <p>Each handler is idempotent. Stripe redelivers, and a webhook that fails here is retried, so every method must be
 * safe to run twice for the same event.
 *
 * <p>The buyer is taken from Imani's own plan row, written before the buyer reached Stripe. Metadata on an event only
 * names the plan; it never names who receives the vouchers.
 */
@Slf4j
public class RecurringPlanService {

    /** The metadata key that names a plan, on the Checkout Session and on the subscription. */
    public static final String PLAN_ID = "plan_id";

    private final RecurringPlanRepository plans;
    private final StripePurchaseRepository purchases;
    private final StripeSubscriptionClient subscriptions;
    /** This deployment's name, lower-case, or null (then no event is treated as foreign). */
    private final String environment;

    public RecurringPlanService(RecurringPlanRepository plans, StripePurchaseRepository purchases,
            StripeSubscriptionClient subscriptions, String environment) {
        this.plans = plans;
        this.purchases = purchases;
        this.subscriptions = subscriptions;
        this.environment = environment == null || environment.isBlank() ? null : environment.trim().toLowerCase();
    }

    /** What an {@code invoice.paid} turns into: the plan, and the purchase to hand the issuer. */
    public record Instalment(RecurringPlan plan, PaidPurchase purchase) {}

    /** True when the metadata names a plan, which makes the event a plan event and never a one-off purchase. */
    public static boolean isPlanEvent(Map<String, String> metadata) {
        return metadata != null && metadata.get(PLAN_ID) != null && !metadata.get(PLAN_ID).isBlank();
    }

    /**
     * The first Checkout Session of a plan completed. Links the subscription to the plan and issues NOTHING: the first
     * instalment's vouchers are issued by its {@code invoice.paid}, so there is exactly one issuance path and a paid
     * session cannot also be recorded as a one-off purchase.
     */
    public void checkoutCompleted(StripeWebhookPayload payload) {
        Optional<RecurringPlan> found = planFor(payload.getMetadata(), true);
        if (found.isEmpty()) {
            return;
        }
        RecurringPlan plan = found.get();
        if (plan.getSubscriptionId() == null && payload.getSubscriptionId() != null) {
            plan.setSubscriptionId(payload.getSubscriptionId());
        }
        if (plan.getCheckoutSessionId() == null) {
            plan.setCheckoutSessionId(payload.getCheckoutSessionId());
        }
        save(plan);
    }

    /**
     * An instalment was paid. Returns the plan and the purchase to record, or null when the event is not ours to
     * act on (another deployment's, or an invoice with nothing paid).
     *
     * @throws RecurringPlanException when the event names a plan Imani cannot match, so it is retried and visible
     */
    public Instalment prepare(StripeWebhookPayload payload) {
        Optional<RecurringPlan> found = planFor(payload.getMetadata(), true);
        if (found.isEmpty()) {
            return null;
        }
        RecurringPlan plan = found.get();
        if (payload.getAmountTotal() == null || payload.getAmountTotal() <= 0) {
            log.info("recurring_plan_invoice_nothing_paid plan={} invoice={}", plan.getId(), payload.getCheckoutSessionId());
            return null;
        }
        if (payload.getConnectedAccountId() == null || !payload.getConnectedAccountId().equals(plan.getConnectedAccountId())) {
            throw new RecurringPlanException("invoice " + payload.getCheckoutSessionId() + " names plan " + plan.getId()
                    + " but arrived on a different account");
        }
        if (payload.getCurrency() == null || !payload.getCurrency().equalsIgnoreCase(plan.getCurrency())) {
            throw new RecurringPlanException("invoice " + payload.getCheckoutSessionId() + " is in "
                    + payload.getCurrency() + " but plan " + plan.getId() + " is in " + plan.getCurrency());
        }
        if (payload.getAmountTotal() != plan.getAmountMinor()) {
            // Paid means owed: what was paid is what is issued. A stall that changed its price in its own Dashboard
            // changes the voucher with it. Worth a line, not a refusal.
            log.info("recurring_plan_amount_differs plan={} invoice={} planned={} paid={}", plan.getId(),
                    payload.getCheckoutSessionId(), plan.getAmountMinor(), payload.getAmountTotal());
        }
        String paymentIntent = payload.getPaymentIntentId();
        if (paymentIntent == null || paymentIntent.isBlank()) {
            paymentIntent = subscriptions.paymentIntentForInvoice(payload.getConnectedAccountId(), payload.getCheckoutSessionId());
        }
        if (paymentIntent == null || paymentIntent.isBlank()) {
            // Refunds and disputes find a purchase by payment intent, so a row without one would be unreachable.
            throw new RecurringPlanException("invoice " + payload.getCheckoutSessionId() + " lists no payment intent yet");
        }
        Map<String, String> carried = new LinkedHashMap<>();
        carried.put("buyer_pubkey", plan.getBuyerPubkey());
        carried.put(PLAN_ID, plan.getId().toString());
        String named = payload.getMetadata().get(StripeGatewayProperties.ENVIRONMENT_METADATA_KEY);
        if (named != null) {
            carried.put(StripeGatewayProperties.ENVIRONMENT_METADATA_KEY, named);
        }
        // checkoutSessionId carries the INVOICE id (V25): unique per paid invoice, so a redelivery is absorbed.
        PaidPurchase purchase = new PaidPurchase(payload.getEventId(), payload.getCheckoutSessionId(), paymentIntent,
                payload.getConnectedAccountId(), payload.getAmountTotal().longValue(), payload.getCurrency(), carried,
                payload.isLivemode());
        return new Instalment(plan, purchase);
    }

    /**
     * The instalment's purchase row is recorded. Updates the plan and, for a fixed-term plan that has now received
     * every instalment, ends the subscription so the buyer is never charged one too many.
     *
     * <p>The count is the number of recorded purchase rows, not a counter this method advances, so running it twice
     * for one event cannot count an instalment twice. If ending the subscription fails this throws and the event is
     * redelivered: the purchase is already recorded (the duplicate is absorbed) and only the cancel is retried.
     */
    public void recorded(RecurringPlan plan, StripeWebhookPayload payload) {
        if (plan.getSubscriptionId() == null && payload.getSubscriptionId() != null) {
            plan.setSubscriptionId(payload.getSubscriptionId());
        }
        long paid = purchases.countByRecurringPlanId(plan.getId());
        Integer total = plan.getTotalInstalments();
        boolean overrun = total != null && paid > total;
        boolean endedAlready = plan.getStatus() == Status.CANCELLED || plan.getStatus() == Status.ORPHANED;
        if (overrun || endedAlready) {
            // A buyer was charged after the plan ended or past its term. The money is recorded and the vouchers
            // owed; a person decides about a refund. Nothing is cancelled or refunded automatically.
            log.warn("[alert] recurring_plan_charged_after_end plan={} status={} paid={} total={}",
                    plan.getId(), plan.getStatus(), paid, total);
            plan.setStatus(Status.ATTENTION);
        } else if (plan.getStatus() == Status.PENDING || plan.getStatus() == Status.PAST_DUE) {
            plan.setStatus(Status.ACTIVE);
        }
        save(plan);
        if (total != null && paid >= total && plan.getCancelRequestedAt() == null && plan.getSubscriptionId() != null) {
            subscriptions.cancelAtPeriodEnd(plan.getConnectedAccountId(), plan.getSubscriptionId());
            plan.setCancelRequestedAt(Instant.now());
            if (plan.getStatus() == Status.ACTIVE) {
                plan.setStatus(Status.COMPLETED);
            }
            save(plan);
        }
    }

    /**
     * The plan's Checkout Session expired without being paid: no subscription exists, so nothing can charge the buyer.
     * Closes the plan, so an abandoned page does not leave a PENDING row behind.
     */
    public void checkoutExpired(StripeWebhookPayload payload) {
        planFor(payload.getMetadata(), false).ifPresent(plan -> {
            if (plan.getStatus() == Status.PENDING && plan.getSubscriptionId() == null) {
                plan.setStatus(Status.CANCELLED);
                save(plan);
            }
        });
    }

    /** An instalment failed. Stripe retries; nothing is issued meanwhile, and the buyer can be told. */
    public void paymentFailed(StripeWebhookPayload payload) {
        planFor(payload.getMetadata(), false).ifPresent(plan -> {
            if (plan.getStatus() == Status.ACTIVE || plan.getStatus() == Status.PENDING) {
                plan.setStatus(Status.PAST_DUE);
                save(plan);
            }
        });
    }

    /** The subscription ended: completed if every instalment was paid, otherwise cancelled. */
    public void subscriptionEnded(StripeWebhookPayload payload) {
        Optional<RecurringPlan> found = planFor(payload.getMetadata(), false);
        if (found.isEmpty() && payload.getSubscriptionId() != null) {
            found = plans.findBySubscriptionId(payload.getSubscriptionId());
        }
        found.ifPresent(plan -> {
            if (plan.getStatus() == Status.COMPLETED || plan.getStatus() == Status.ORPHANED
                    || plan.getStatus() == Status.ATTENTION) {
                return;
            }
            long paid = purchases.countByRecurringPlanId(plan.getId());
            Integer total = plan.getTotalInstalments();
            plan.setStatus(total != null && paid >= total ? Status.COMPLETED : Status.CANCELLED);
            save(plan);
        });
    }

    /**
     * Finds the plan an event names.
     *
     * @param mustExist true to throw when the plan is unknown (money may have moved), false to ignore it
     * @return empty when there is no plan id, the plan belongs to another deployment, or (when {@code mustExist} is
     *         false) it is unknown
     */
    private Optional<RecurringPlan> planFor(Map<String, String> metadata, boolean mustExist) {
        if (!isPlanEvent(metadata)) {
            return Optional.empty();
        }
        if (RecordingPurchaseListener.foreignEnvironment(environment, metadata) != null) {
            log.info("recurring_plan_foreign_environment plan={}", metadata.get(PLAN_ID));
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(metadata.get(PLAN_ID).trim());
        } catch (IllegalArgumentException e) {
            log.warn("recurring_plan_malformed_id value_length={}", metadata.get(PLAN_ID).length());
            return Optional.empty();
        }
        Optional<RecurringPlan> plan = plans.findById(id);
        if (plan.isEmpty() && mustExist) {
            throw new RecurringPlanException("event names plan " + id + " but Imani has no such plan");
        }
        return plan;
    }

    private void save(RecurringPlan plan) {
        plan.setUpdatedAt(Instant.now());
        plans.save(plan);
    }
}
