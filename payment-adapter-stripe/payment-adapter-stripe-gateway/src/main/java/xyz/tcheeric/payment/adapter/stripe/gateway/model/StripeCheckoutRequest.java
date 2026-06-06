package xyz.tcheeric.payment.adapter.stripe.gateway.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class StripeCheckoutRequest {
    String quoteId;
    Integer amount;
    String description;
    String currency;
    String idempotencyKey;
}
