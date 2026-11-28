package xyz.tcheeric.payment.adapter.stripe.connect.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** The Stripe page to send the farm's browser to, and when the connection request stops being valid. */
public record StripeConnectOAuthStartResponse(
        @JsonProperty("authorize_url") String authorizeUrl,
        @JsonProperty("expires_at") Instant expiresAt
) {}
