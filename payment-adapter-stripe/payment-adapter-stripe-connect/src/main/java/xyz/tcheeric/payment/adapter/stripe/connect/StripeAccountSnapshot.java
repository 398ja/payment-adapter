package xyz.tcheeric.payment.adapter.stripe.connect;

import java.util.List;

public record StripeAccountSnapshot(
        String merchantPubkey,
        String stripeAccountId,
        boolean onboardingComplete,
        boolean chargesEnabled,
        boolean payoutsEnabled,
        boolean detailsSubmitted,
        String defaultCurrency,
        List<String> requirementsDue,
        String disabledReason,
        String country,
        String email,
        String accountType,
        String feePayer,
        String lossesPayer,
        String dashboardType
) {
    /** The shape before responsibilities were recorded. Kept so callers that do not read them stay unchanged. */
    public StripeAccountSnapshot(
            String merchantPubkey,
            String stripeAccountId,
            boolean onboardingComplete,
            boolean chargesEnabled,
            boolean payoutsEnabled,
            boolean detailsSubmitted,
            String defaultCurrency,
            List<String> requirementsDue,
            String disabledReason,
            String country,
            String email) {
        this(merchantPubkey, stripeAccountId, onboardingComplete, chargesEnabled, payoutsEnabled, detailsSubmitted,
                defaultCurrency, requirementsDue, disabledReason, country, email, null, null, null, null);
    }
}
