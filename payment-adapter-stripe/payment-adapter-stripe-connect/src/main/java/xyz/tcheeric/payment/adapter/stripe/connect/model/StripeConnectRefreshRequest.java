package xyz.tcheeric.payment.adapter.stripe.connect.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StripeConnectRefreshRequest(
        @JsonProperty("return_url") String returnUrl,
        @JsonProperty("refresh_url") String refreshUrl
) {}
