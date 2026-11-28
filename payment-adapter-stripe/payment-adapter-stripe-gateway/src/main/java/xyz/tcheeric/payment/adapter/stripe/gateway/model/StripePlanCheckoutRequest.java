package xyz.tcheeric.payment.adapter.stripe.gateway.model;

import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * One instalment-plan Checkout Session: a Stripe subscription on the STALL's connected account.
 *
 * <p>Always a direct charge. There is deliberately no platform fee: Imani takes nothing on a plan in v1, and a
 * subscription's fee can only be a percentage (docs/specs/payment-adapter-stripe-recurring-spec.md).
 */
@Value
@Builder
public class StripePlanCheckoutRequest {

    /** The stall's {@code acct_...}. Required: a plan is never a platform charge. */
    String connectedAccountId;

    /** Imani's own record of the plan, carried on the session and on the subscription. */
    String planId;

    /** One instalment, in minor units. */
    long amountMinor;

    String currency;

    String description;

    /**
     * How often an instalment is taken, as Stripe names it: {@code week}, {@code month} or {@code year}. Null means
     * {@code month}, the only interval before stalls could choose.
     */
    String interval;

    /** Carried on the session AND on the subscription, so every invoice can name the plan. */
    Map<String, String> metadata;

    String successUrl;

    String cancelUrl;

    String idempotencyKey;
}
