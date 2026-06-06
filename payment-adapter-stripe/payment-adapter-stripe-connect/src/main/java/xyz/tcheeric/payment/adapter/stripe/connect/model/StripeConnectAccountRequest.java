package xyz.tcheeric.payment.adapter.stripe.connect.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StripeConnectAccountRequest(
        @JsonProperty("merchant_pubkey") String merchantPubkey,
        @JsonProperty("return_url") String returnUrl,
        @JsonProperty("refresh_url") String refreshUrl
) {}
