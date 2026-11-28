package xyz.tcheeric.payment.adapter.stripe.connect.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What Stripe appended to the redirect URI. The merchant is NOT in here: it comes from the stored state. */
public record StripeConnectOAuthCompleteRequest(
        @JsonProperty("code") String code,
        @JsonProperty("state") String state
) {}
