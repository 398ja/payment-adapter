package xyz.tcheeric.payment.adapter.paypal.connect.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** {@code StripeConnectAccountResponse}'s shape, so the wallet's Payments screen reads both alike. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaypalConnectAccountResponse(
        @JsonProperty("merchant_pubkey") String merchantPubkey,
        @JsonProperty("paypal_merchant_id") String paypalMerchantId,
        @JsonProperty("onboarding_url") String onboardingUrl,
        @JsonProperty("status") String status,
        @JsonProperty("onboarding_complete") boolean onboardingComplete,
        @JsonProperty("charges_enabled") boolean chargesEnabled,
        @JsonProperty("payouts_enabled") boolean payoutsEnabled,
        @JsonProperty("details_submitted") boolean detailsSubmitted,
        @JsonProperty("default_currency") String defaultCurrency,
        @JsonProperty("requirements_due") List<String> requirementsDue
) {}
