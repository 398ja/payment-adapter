package xyz.tcheeric.payment.adapter.paypal.webhook.reconcile;

import java.util.List;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalCapture;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalDispute;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalOrder;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalOrdersClient;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.PaymentStateReader;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileException;
import xyz.tcheeric.payment.adapter.stripe.webhook.reconcile.StripeReconcileSource.PaymentState;

/**
 * A PayPal capture's live state, in the terms the shared sweeps read (ADR 0002): the counterpart of
 * {@code StripeSdkReconcileSource#paymentState}.
 *
 * <ul>
 *   <li><b>Refunded</b>: the order's completed refunds, summed, or the whole amount for a capture
 *       PayPal reports {@code REFUNDED} (a reversal leaves no refund object to sum).</li>
 *   <li><b>Disputed</b>: PayPal holds a dispute on the capture that is not yet resolved, which is
 *       what Stripe's {@code charge.disputed} means.</li>
 * </ul>
 *
 * <p>Read as the seller, so a capture on anyone else's account is not found.
 */
public class PaypalPaymentStateReader implements PaymentStateReader {

    private final PaypalOrdersClient paypal;

    public PaypalPaymentStateReader(PaypalOrdersClient paypal) {
        this.paypal = paypal;
    }

    @Override
    public PaymentState paymentState(String merchantId, String captureId) {
        if (merchantId == null || captureId == null) {
            throw new StripeReconcileException("PayPal purchase has no merchant or capture to read", null, false);
        }
        try {
            PaypalCapture capture = paypal.getCapture(merchantId, captureId);
            long amount = capture.amountMinor() == null ? 0L : capture.amountMinor();
            long refunded = 0L;
            if (capture.orderId() != null) {
                PaypalOrder order = paypal.getOrder(merchantId, capture.orderId());
                refunded = order.refundedMinor();
            }
            if (capture.fullyRefunded() || "REVERSED".equalsIgnoreCase(capture.status())) {
                refunded = Math.max(refunded, amount);
            }
            List<PaypalDispute> disputes = paypal.disputesFor(merchantId, captureId);
            boolean disputed = disputes.stream().anyMatch(PaypalDispute::open);
            return new PaymentState(captureId, amount, refunded, disputed);
        } catch (PaypalApiException e) {
            throw new StripeReconcileException("PayPal capture " + captureId + " could not be read: " + e.getMessage(),
                    e, e.isRateLimited());
        }
    }
}
