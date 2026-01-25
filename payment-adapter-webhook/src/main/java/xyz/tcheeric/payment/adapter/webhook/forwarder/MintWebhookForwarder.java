package xyz.tcheeric.payment.adapter.webhook.forwarder;

/**
 * Forwards payment confirmations to cashu-mint.
 *
 * <p>When a payment is confirmed via webhook (from phoenixd or other payment providers),
 * this forwarder notifies the mint so it can update the quote status in real-time.
 */
public interface MintWebhookForwarder {

    /**
     * Notify the mint that a payment was received.
     *
     * @param notification the payment notification to forward
     * @return true if successfully delivered, false otherwise
     */
    boolean notifyPaymentReceived(PaymentNotification notification);

    /**
     * Check if the forwarder is enabled.
     *
     * @return true if forwarding is enabled
     */
    boolean isEnabled();
}
