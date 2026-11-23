package xyz.tcheeric.payment.adapter.paypal.connect.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code StripeConnectAccountRequest}'s shape. {@code refresh_url} is accepted and unused: PayPal has none. */
public record PaypalConnectAccountRequest(
        @JsonProperty("merchant_pubkey") String merchantPubkey,
        @JsonProperty("return_url") String returnUrl,
        @JsonProperty("refresh_url") String refreshUrl
) {}
