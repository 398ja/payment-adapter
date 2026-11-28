package xyz.tcheeric.payment.adapter.core.model.entity.stripe;

import java.util.Optional;

/**
 * Whether a connected account may run instalment plans: the stall must pay Stripe's fees and Stripe must bear a
 * negative balance (spec R4). On a legacy Express account Imani would pay the Billing fee, the Connect fees and
 * carry the loss, which is exactly what plans must not do.
 *
 * <p>Null means "Stripe did not report it". It is refused while {@code requireControls} is on, because an unknown
 * answer is not a safe one.
 */
public final class RecurringEligibility {

    private RecurringEligibility() {}

    /**
     * Whether the stall may offer plans, as its own status screen reports it. Always strict: an account Stripe did
     * not report on is not eligible, whatever {@code requireControls} says, because a stall told "available" on a
     * guess would publish an offer every buyer then sees refused.
     */
    public static boolean eligible(ConnectedStripeAccount account) {
        return account != null && account.getStripeAccountId() != null && refusal(account, true).isEmpty();
    }

    /** The reason the account may not run plans, or empty when it may. Text is safe to show a buyer. */
    public static Optional<String> refusal(ConnectedStripeAccount account, boolean requireControls) {
        String fees = account.getFeePayer();
        String losses = account.getLossesPayer();
        if (fees != null && !"account".equals(fees)) {
            return Optional.of("this stall's Stripe account is not set up for instalment plans");
        }
        if (losses != null && !"stripe".equals(losses)) {
            return Optional.of("this stall's Stripe account is not set up for instalment plans");
        }
        if (requireControls && (fees == null || losses == null)) {
            return Optional.of("this stall's Stripe account could not be checked for instalment plans");
        }
        return Optional.empty();
    }
}
