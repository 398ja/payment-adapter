package xyz.tcheeric.payment.adapter.paypal.connect;

/**
 * Whether a PayPal seller can take A CARD today: {@code CardChargeCapability}'s rule, for PayPal
 * (ADR 0002).
 *
 * <p>Stripe taught that one flag is not the answer: {@code charges_enabled} read true on an account
 * that could not take a card, and the truth surfaced in front of a buyer. PayPal's flags have the
 * same trap. {@code payments_receivable} is true for a seller who never confirmed their email, and
 * PayPal then holds every payment unclaimed; and neither flag says this platform may create orders
 * for them. So all three must hold:
 *
 * <ul>
 *   <li>{@code payments_receivable}: PayPal has not restricted the account;</li>
 *   <li>{@code primary_email_confirmed}: payments reach the seller rather than waiting on them;</li>
 *   <li>the {@code PAYMENT} permission granted to THIS platform's app, without which every order
 *       create answers {@code PERMISSION_DENIED} after the buyer pressed Pay.</li>
 * </ul>
 *
 * <p>One copy, used by both the API refresh and the onboarding webhooks, for the reason
 * {@code CardChargeCapability} gives: two copies of a rule that must agree drift.
 */
public final class PaypalCardCapability {

    private PaypalCardCapability() {
    }

    public static boolean canTakeCards(PaypalSellerStatus status) {
        return status != null
                && status.merchantId() != null
                && status.paymentsReceivable()
                && status.primaryEmailConfirmed()
                && status.grants(PaypalSellerStatus.PAYMENT_SCOPE);
    }
}
