package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.util.regex.Pattern;

/**
 * The shape of a card purchase's processor reference, the id a coupon's warrant carries and
 * {@code stripe_purchase.payment_intent_id} holds (ADR 0002).
 *
 * <ul>
 *   <li>Stripe: a PaymentIntent, {@code pi_…}.</li>
 *   <li>PayPal: a capture id, 17 upper-case letters and digits ({@code 3C679366HH908993F}).</li>
 * </ul>
 *
 * <p>Checked for shape only, to keep a malformed value out of a query and a log line. Whether the
 * reference is real is the ledger's answer, never this.
 */
public final class ProcessorReference {

    private static final Pattern STRIPE = Pattern.compile("^pi_[A-Za-z0-9_]{1,250}$");
    private static final Pattern PAYPAL = Pattern.compile("^[A-Z0-9]{17}$");

    private ProcessorReference() {
    }

    public static boolean isStripe(String reference) {
        return reference != null && STRIPE.matcher(reference).matches();
    }

    public static boolean isPaypal(String reference) {
        return reference != null && PAYPAL.matcher(reference).matches();
    }

    /** A reference either processor could have issued. */
    public static boolean isWellFormed(String reference) {
        return isStripe(reference) || isPaypal(reference);
    }
}
