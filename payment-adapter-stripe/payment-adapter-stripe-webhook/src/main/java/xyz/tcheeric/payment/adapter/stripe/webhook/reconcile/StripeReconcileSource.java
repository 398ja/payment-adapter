package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the Stripe reconciler reads from Stripe (payment-adapter#272). Two reads, both on the
 * connected account ({@code Stripe-Account}), because a purchase is a direct charge on the stall.
 */
public interface StripeReconcileSource {

    /**
     * One page of the account's {@code status=complete} Checkout Sessions created in
     * {@code [from, until]}, newest first as Stripe lists them.
     *
     * @param startingAfter the last session of the previous page, or null for the first
     * @throws StripeReconcileException when Stripe cannot be read; nothing is skipped on failure
     */
    SessionPage completeSessions(String accountId, Instant from, Instant until, String startingAfter, int limit);

    /**
     * The payment's live state, read from its PaymentIntent and latest Charge. Read before any
     * row is recorded or moved, so a refunded or disputed payment is never made mintable.
     *
     * @throws StripeReconcileException when Stripe cannot be read
     */
    PaymentState paymentState(String accountId, String paymentIntentId);

    /** A complete Checkout Session, in the terms the webhook's {@code PaidPurchase} needs. */
    record CompletedSession(
            String id,
            Instant created,
            String paymentStatus,
            String paymentIntentId,
            Long amountTotal,
            String currency,
            Map<String, String> metadata,
            boolean livemode) {

        /** Paid, with a PaymentIntent to check: the only sessions the reconciler records. */
        public boolean paid() {
            return "paid".equalsIgnoreCase(paymentStatus) && paymentIntentId != null && !paymentIntentId.isBlank();
        }
    }

    record SessionPage(List<CompletedSession> sessions, boolean hasMore) {

        public String lastId() {
            return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1).id();
        }
    }

    /**
     * @param chargeId      the latest charge, null when the intent has none
     * @param amountMinor   the charge's amount
     * @param refundedMinor cumulative {@code amount_refunded} (the full amount when {@code refunded})
     * @param disputed      the charge carries a dispute
     */
    record PaymentState(String chargeId, long amountMinor, long refundedMinor, boolean disputed) {

        public boolean refunded() {
            return refundedMinor > 0;
        }

        /** Money is exactly where the buyer paid it: the only state a purchase may be issued in. */
        public boolean intact() {
            return !refunded() && !disputed;
        }
    }
}
