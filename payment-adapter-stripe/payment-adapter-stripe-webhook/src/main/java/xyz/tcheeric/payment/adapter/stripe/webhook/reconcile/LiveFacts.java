package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.PaymentState;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener.ReversedPurchase;

/**
 * Hands a live refund or dispute to the listener exactly as the webhook would (#267), so the
 * reconciler and the webhook cannot disagree on what a refund does to a row: before issue it is
 * REFUNDED, after issue the facts are recorded and nothing else changes (#265).
 */
final class LiveFacts {

    private LiveFacts() {
    }

    static void record(StripePurchaseListener listener, String eventId, String accountId, String paymentIntentId,
            PaymentState live) {
        if (live.refunded()) {
            LostPurchaseRecorder.call(() -> listener.onPurchaseReversed(new ReversedPurchase(
                    eventId + ":live-refund", paymentIntentId, live.chargeId(), accountId,
                    live.refundedMinor(), live.amountMinor(), false)));
        }
        if (live.disputed()) {
            LostPurchaseRecorder.call(() -> listener.onPurchaseReversed(new ReversedPurchase(
                    eventId + ":live-dispute", paymentIntentId, live.chargeId(), accountId,
                    null, live.amountMinor(), true)));
        }
    }
}
