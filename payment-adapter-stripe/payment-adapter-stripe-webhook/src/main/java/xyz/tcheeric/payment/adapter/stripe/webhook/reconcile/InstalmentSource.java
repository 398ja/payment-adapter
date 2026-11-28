package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import java.time.Instant;
import java.util.List;

/**
 * What the instalment reconciler reads from Stripe, both on the stall's connected account. A separate interface from
 * {@link StripeReconcileSource} so the one-off reconciler's source (and its test doubles) is untouched.
 */
public interface InstalmentSource {

    /**
     * The paid invoices of one subscription, newest first, at most {@code limit}.
     *
     * @throws StripeReconcileException when Stripe cannot be read; nothing is skipped on failure
     */
    List<PaidInvoice> paidInvoices(String accountId, String subscriptionId, int limit);

    /**
     * A plan's Checkout Session, to learn whether its buyer finished (and which subscription it created) when the
     * webhook that would have said so was lost.
     *
     * @throws StripeReconcileException when Stripe cannot be read
     */
    PlanSession planSession(String accountId, String sessionId);

    /**
     * One paid invoice. {@code paymentIntentId} is often null: newer API versions no longer put it on the invoice, and
     * the plan service reads it from the invoice's payments only for an invoice that is actually missing.
     */
    record PaidInvoice(String id, String paymentIntentId, long amountPaid, String currency, Instant paidAt,
            boolean livemode) {}

    /** A Checkout Session's state: {@code open}, {@code complete} or {@code expired}, and any subscription it made. */
    record PlanSession(String status, String subscriptionId) {}
}
