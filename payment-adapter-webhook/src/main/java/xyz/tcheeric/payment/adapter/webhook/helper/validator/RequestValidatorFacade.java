package xyz.tcheeric.payment.adapter.webhook.helper.validator;

import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.webhook.helper.PhoenixdWebhookRequest;

import java.util.UUID;

public class RequestValidatorFacade {

    /**
     * Validate a webhook request and return the associated payment.
     *
     * <p>Currently supports phoenixd-formatted webhooks identified by presence of parameters
     * like {@code type}, {@code amountSat}, {@code paymentHash}, and {@code externalId}.</p>
     *
     * @param request incoming HTTP servlet request
     * @return the validated payment
     * @throws IllegalArgumentException if validation fails
     */
    public static GatewayPayment validate(@NonNull HttpServletRequest request) {
        PhoenixdWebhookRequest phoenixdWebhookRequest = new PhoenixdWebhookRequest();
        String amount = request.getParameter("amountSat");
        if (amount != null && !amount.isBlank()) {
            try {
                phoenixdWebhookRequest.setAmountSat(Integer.parseInt(amount));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid amountSat: must be an integer", ex);
            }
        }
        phoenixdWebhookRequest.setType(request.getParameter("type"));
        phoenixdWebhookRequest.setPaymentHash(request.getParameter("paymentHash"));
        phoenixdWebhookRequest.setExternalId(request.getParameter("externalId"));

        // Short-circuit if required identifiers are missing to avoid remote calls
        if (phoenixdWebhookRequest.getExternalId() == null || phoenixdWebhookRequest.getExternalId().isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: externalId");
        }
        PhoenixWebhookValidator phoenixWebhookValidator = new PhoenixWebhookValidator(phoenixdWebhookRequest);
        return phoenixWebhookValidator.validate();

    }

}
