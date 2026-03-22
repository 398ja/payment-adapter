package xyz.tcheeric.payment.adapter.stripe.gateway.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class StripeCheckoutSession {
    String sessionId;
    String sessionUrl;
    String paymentStatus;
    String status;
    String paymentIntentId;
    long expiresAtEpochSeconds;
    boolean livemode;
}
