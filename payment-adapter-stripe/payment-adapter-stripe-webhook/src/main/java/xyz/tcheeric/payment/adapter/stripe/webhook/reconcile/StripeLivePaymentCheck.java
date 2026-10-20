package xyz.tcheeric.payment.adapter.stripe.webhook.reconcile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.LivePayment;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.LivePaymentCheck;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.LivePaymentUnavailableException;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.PaymentState;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;

/**
 * {@link LivePaymentCheck} over the reconciler's own live read (#286, reusing #272/#282): the same
 * PaymentIntent and latest-charge read on the connected account, and the same {@link LiveFacts} hand-off
 * to the listener the webhook uses, so a refund found here lands on the row exactly as
 * {@code charge.refunded} would have.
 *
 * <p>Facts are recorded only when they are news to the row. Recording bumps {@code reversed_at}, which
 * re-raises an acknowledged reversal, and a re-read that changes nothing must not do that.
 */
@Slf4j
@RequiredArgsConstructor
public class StripeLivePaymentCheck implements LivePaymentCheck {

    private final StripeReconcileSource stripe;
    private final StripePurchaseListener listener;

    @Override
    public LivePayment refresh(StripePurchase purchase) {
        PaymentState live = read(purchase);
        boolean news = isNews(purchase, live);
        if (news) {
            log.warn("[alert] Live check of purchase {} found what no webhook delivered: refunded {} of {},"
                            + " disputed {}. Recorded; check the endpoint's enabled_events",
                    purchase.getEventId(), live.refundedMinor(), live.amountMinor(), live.disputed());
            LiveFacts.record(listener, purchase.getEventId(), purchase.getConnectedAccountId(),
                    purchase.getPaymentIntentId(), live);
        }
        return new LivePayment(live.refundedMinor(), live.amountMinor(), live.disputed(), news);
    }

    private PaymentState read(StripePurchase purchase) {
        try {
            return stripe.paymentState(purchase.getConnectedAccountId(), purchase.getPaymentIntentId());
        } catch (StripeReconcileException e) {
            throw new LivePaymentUnavailableException(e.getMessage(), e);
        }
    }

    private static boolean isNews(StripePurchase purchase, PaymentState live) {
        long known = purchase.getRefundedAmountMinor() == null ? 0L : purchase.getRefundedAmountMinor();
        return live.refundedMinor() > known || (live.disputed() && !purchase.isDisputed());
    }
}
