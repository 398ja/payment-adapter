package xyz.tcheeric.gateway.webhook.helper.validator;

import xyz.tcheeric.gateway.model.entity.GatewayPayment;

public interface WebhookValidator {

    GatewayPayment validate();
}
