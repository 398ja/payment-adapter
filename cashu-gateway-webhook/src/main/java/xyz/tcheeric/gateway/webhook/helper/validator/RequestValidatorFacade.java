package xyz.tcheeric.gateway.webhook.helper.validator;

import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.webhook.helper.PhoenixdWebhookRequest;
import xyz.tcheeric.gateway.webhook.helper.validator.PhoenixWebhookValidator;
import xyz.tcheeric.util.ConfigUtil;

public class RequestValidatorFacade {

    public static GatewayPayment validate(@NonNull HttpServletRequest request) {

        String prefix = request.getParameter("wid");
        ConfigUtil configUtil = new ConfigUtil(prefix);

        String webhookId = configUtil.get("wid");
        if(webhookId == null) {
            throw new IllegalArgumentException("Invalid webhook id");
        }

        switch (webhookId) {
            case "phoenixd":
                PhoenixdWebhookRequest phoenixdWebhookRequest = new PhoenixdWebhookRequest();
                phoenixdWebhookRequest.setType(request.getParameter("type"));
                phoenixdWebhookRequest.setAmountSat(Integer.parseInt(request.getParameter("amountSat")));
                phoenixdWebhookRequest.setPaymentHash(request.getParameter("paymentHash"));
                phoenixdWebhookRequest.setExternalId(request.getParameter("externalId"));
                PhoenixWebhookValidator phoenixWebhookValidator = new PhoenixWebhookValidator(phoenixdWebhookRequest);
                return phoenixWebhookValidator.validate();
            default:
                throw new IllegalArgumentException("Unknown webhook request id: " + webhookId);
        }

    }

}