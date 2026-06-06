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
        String email
) {}
