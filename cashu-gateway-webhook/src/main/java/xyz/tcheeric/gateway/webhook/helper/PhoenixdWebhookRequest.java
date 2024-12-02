package xyz.tcheeric.gateway.webhook.helper;

import lombok.Data;

@Data
public class PhoenixdWebhookRequest implements WebhookRequest {
    private String type;
    private Integer amountSat;
    private String paymentHash;
    private String externalId;
}
