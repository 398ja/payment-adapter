package xyz.tcheeric.gateway.webhook.helper.validator;

import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.enums.State;
import xyz.tcheeric.gateway.webhook.helper.PhoenixdWebhookRequest;

import java.util.UUID;

public class RequestValidatorFacade {

    /**
     * Validate a webhook request and return the associated payment.
     *
     * <p>The request must include a {@code wid} parameter identifying the webhook source
     * (e.g. {@code phoenixd}). If absent, the system property {@code wid} will be used.</p>
     *
     * @param request incoming HTTP servlet request
     * @return the validated payment
     * @throws IllegalArgumentException if the webhook id cannot be determined or is unknown
     */
    public static GatewayPayment validate(@NonNull HttpServletRequest request) {
        String webhookId = request.getParameter("wid");
        if (webhookId == null) {
            webhookId = System.getProperty("wid");
        }
        if (webhookId == null) {
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
            case "dummy":
                GatewayPayment payment = new GatewayPayment();
                payment.setState(State.PAID);
                payment.setPaymentHash(UUID.randomUUID().toString());
                payment.setPaymentId(UUID.randomUUID().toString());
                payment.setAmount(10);
                payment.setPaymentPreimage(UUID.randomUUID().toString());
                PaymentClient paymentClient = new PaymentClient();
                paymentClient.create(payment);
                return payment;
            default:
                throw new IllegalArgumentException("Unknown webhook request id: " + webhookId);
        }

    }

}