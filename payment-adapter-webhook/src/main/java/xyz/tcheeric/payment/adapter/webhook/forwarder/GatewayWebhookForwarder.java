package xyz.tcheeric.payment.adapter.webhook.forwarder;

/**
 * Forwards payment confirmations to the gateway-app for post-payment processing
 * (e.g., voucher minting saga for cash payments).
 *
 * <p>This is distinct from {@link MintWebhookForwarder} which notifies the cashu-mint
 * about Lightning payment confirmations. The gateway forwarder is used for payment
 * methods that require gateway-side orchestration after confirmation.
 */
public interface GatewayWebhookForwarder {

    /**
     * Notify the gateway that a payment was confirmed.
     *
     * @param notification the payment notification to forward
     * @return true if successfully delivered, false otherwise
     */
    boolean notifyPaymentConfirmed(PaymentNotification notification);

    /**
     * Check if the forwarder is enabled.
     *
     * @return true if forwarding is enabled
     */
    boolean isEnabled();
}
