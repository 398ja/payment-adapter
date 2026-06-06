package xyz.tcheeric.payment.adapter.stripe.connect.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StripeConnectErrorResponse(
        @JsonProperty("code") String code,
        @JsonProperty("message") String message
) {}
