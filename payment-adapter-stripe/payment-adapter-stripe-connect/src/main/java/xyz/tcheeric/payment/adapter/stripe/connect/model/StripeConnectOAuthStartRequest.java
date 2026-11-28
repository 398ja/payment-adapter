package xyz.tcheeric.payment.adapter.stripe.connect.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StripeConnectOAuthStartRequest(
        @JsonProperty("merchant_pubkey") String merchantPubkey
) {}
