package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;

/**
 * Re-reads a purchase's PaymentIntent, latest charge, refunds and dispute live from Stripe, on the
 * stall's connected account (#286 item 3).
 *
 * <p>A refund or dispute Stripe reports that the row does not yet carry is recorded on the way, by the
 * same listener path the webhook uses, so the row and Stripe agree before anyone acts on the answer.
 */
public interface LivePaymentCheck {

    /**
     * @return what Stripe says now
     * @throws LivePaymentUnavailableException when Stripe cannot be asked; the caller must fail closed
     */
    LivePayment refresh(StripePurchase purchase);

    /** For a deployment with Stripe off: nothing can be verified, so nothing is ever verified. */
    static LivePaymentCheck unconfigured() {
        return purchase -> {
            throw new LivePaymentUnavailableException(
                    "Stripe is not enabled, so the payment of " + purchase.getEventId() + " cannot be read live");
        };
    }
}
