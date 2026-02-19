package xyz.tcheeric.payment.adapter.webhook.helper.validator;

import lombok.Getter;
import lombok.NonNull;
import xyz.tcheeric.payment.adapter.webhook.helper.WebhookRequest;

@Getter
public abstract class BaseWebhookValidator implements WebhookValidator {

    private final WebhookRequest webhookRequest;

    public BaseWebhookValidator(@NonNull WebhookRequest webhookRequest) {
        this.webhookRequest = webhookRequest;
    }
}
