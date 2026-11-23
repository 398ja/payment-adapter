package xyz.tcheeric.payment.adapter.paypal.connect.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaypalConnectWebhookResponse(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("status") String status
) {}
