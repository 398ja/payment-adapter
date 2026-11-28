package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

/** How a stall's Stripe account came to be connected to Imani. */
public enum StripeConnectionType {
    /** Imani created the account and sent the stall through Stripe-hosted onboarding. */
    CREATED,
    /** The stall already had a Stripe account and connected it through OAuth. */
    OAUTH
}
