package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

/**
 * What Stripe says about a purchase's payment right now (#286), read live rather than taken from the
 * row, because a missed refund or dispute webhook leaves the row believing the money is still there.
 *
 * @param refundedMinor cumulative refunded amount of the latest charge
 * @param amountMinor   the charge's amount
 * @param disputed      the charge carries a dispute
 * @param newFacts      the read found something the row did not yet record, and it is now recorded
 */
public record LivePayment(long refundedMinor, long amountMinor, boolean disputed, boolean newFacts) {

    public boolean refunded() {
        return refundedMinor > 0;
    }

    /** Money is exactly where the buyer paid it. */
    public boolean intact() {
        return !refunded() && !disputed;
    }
}
