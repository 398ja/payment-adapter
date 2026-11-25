package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.PaymentState;

/**
 * Reads one recorded purchase's payment live from its processor (ADR 0002): the read the stale and
 * parked sweeps, and the operator's live check, make before they move a row.
 *
 * <p>Stripe reads a PaymentIntent and its latest charge; PayPal reads a capture and its disputes.
 * Both answer in the same terms, so a refund found by either lands on the row the same way.
 */
public interface PaymentStateReader {

    /**
     * @param accountId       the row's {@code connected_account_id}: a Stripe {@code acct_…} or a
     *                        PayPal merchant id
     * @param paymentIntentId the row's processor reference: a {@code pi_…} or a PayPal capture id
     * @throws StripeReconcileException when the processor cannot be read
     */
    PaymentState paymentState(String accountId, String paymentIntentId);
}
