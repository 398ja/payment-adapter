package xyz.tcheeric.payment.adapter.stripe.connect.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A stall's connected account as its Card payments screen needs it.
 *
 * <p>The last five fields serve instalment plans (imani-wallet monthly payments spec): how the account was connected,
 * who pays Stripe's fees and bears losses, whether plans may run on it, and whether any plan is charging a buyer now
 * (which blocks disconnecting). {@code instalments_eligible} and {@code active_plans} are always present, false when
 * unknown, so a reader that defaults a missing field to false reads the same answer.
 */
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
        @JsonProperty("requirements_due") List<String> requirementsDue,
        @JsonProperty("connection_type") String connectionType,
        @JsonProperty("fee_payer") String feePayer,
        @JsonProperty("losses_payer") String lossesPayer,
        @JsonProperty("instalments_eligible") boolean instalmentsEligible,
        @JsonProperty("active_plans") boolean activePlans
) {

    /** An account with nothing known about plans: not eligible, none running. */
    public StripeConnectAccountResponse(String merchantPubkey, String stripeAccountId, String onboardingUrl,
            String status, boolean onboardingComplete, boolean chargesEnabled, boolean payoutsEnabled,
            boolean detailsSubmitted, String defaultCurrency, List<String> requirementsDue) {
        this(merchantPubkey, stripeAccountId, onboardingUrl, status, onboardingComplete, chargesEnabled,
                payoutsEnabled, detailsSubmitted, defaultCurrency, requirementsDue, null, null, null, false, false);
    }
}
