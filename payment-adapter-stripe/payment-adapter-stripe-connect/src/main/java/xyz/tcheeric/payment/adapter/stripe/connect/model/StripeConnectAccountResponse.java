package xyz.tcheeric.payment.adapter.stripe.connect.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StripeConnectAccountResponse(
        @JsonProperty("merchant_pubkey") String merchantPubkey,
        @JsonProperty("stripe_account_id") String stripeAccountId,
        @JsonProperty("onboarding_url") String onboardingUrl,
        @JsonProperty("status") String status,
        @JsonProperty("onboarding_complete") boolean onboardingComplete,
        @JsonProperty("charges_enabled") boolean chargesEnabled,
        @JsonProperty("payouts_enabled") boolean payoutsEnabled,
        @JsonProperty("details_submitted") boolean detailsSubmitted,
        @JsonProperty("default_currency") String defaultCurrency,
        @JsonProperty("requirements_due") List<String> requirementsDue
) {}
