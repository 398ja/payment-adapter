package xyz.tcheeric.payment.adapter.stripe.webhook.recurring;

/** The two Stripe calls an instalment plan needs after Checkout, both made on the stall's connected account. */
public interface StripeSubscriptionClient {

    /**
     * The payment intent that paid {@code invoiceId}, or null if Stripe lists none. Newer API versions no longer
     * put {@code payment_intent} on an invoice, so it is read from the invoice's payments.
     *
     * @throws RuntimeException when Stripe could not be asked, so the webhook is retried
     */
    String paymentIntentForInvoice(String connectedAccountId, String invoiceId);

    /**
     * Ends the subscription after the current period instead of renewing it. Idempotent: asking again for a
     * subscription already set to end changes nothing.
     *
     * @throws RuntimeException when Stripe could not be asked
     */
    void cancelAtPeriodEnd(String connectedAccountId, String subscriptionId);

    /**
     * Expires a plan's still-open Checkout Session, so a buyer who stopped a plan they never finished cannot complete
     * Stripe's page afterwards and start a subscription on a plan Imani already closed. Idempotent: a session that
     * has already expired is not an error.
     *
     * @throws RuntimeException when Stripe could not be asked, or the session has already completed
     */
    void expireCheckoutSession(String connectedAccountId, String checkoutSessionId);
}
