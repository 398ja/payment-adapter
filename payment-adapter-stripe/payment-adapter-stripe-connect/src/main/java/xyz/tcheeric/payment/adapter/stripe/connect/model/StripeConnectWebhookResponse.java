package xyz.tcheeric.payment.adapter.stripe.connect.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StripeConnectWebhookResponse(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("status") String status
) {}
