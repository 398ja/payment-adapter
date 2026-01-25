package xyz.tcheeric.payment.adapter.webhook.helper.validator;

import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;

public interface WebhookValidator {

    GatewayPayment validate();
}
