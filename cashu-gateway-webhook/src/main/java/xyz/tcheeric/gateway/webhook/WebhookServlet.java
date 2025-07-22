package xyz.tcheeric.gateway.webhook;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.enums.State;
import xyz.tcheeric.gateway.webhook.helper.validator.RequestValidatorFacade;

import java.time.Instant;

@WebServlet(name = "WebhookServlet", value = "/webhook")
@Slf4j
public class WebhookServlet extends HttpServlet {

    @Override
    public void init() {
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) {
        try {
            // Validate the request
            GatewayPayment payment = RequestValidatorFacade.validate(request);

            log.info("Confirming the payment: paymentId={}", payment.getPaymentId());
            payment.setState(State.CONFIRMED);
            payment.setConfirmedDate(Instant.now());

            log.debug("Updating the payment: payment={}", payment);
            PaymentClient paymentClient = new PaymentClient();
            paymentClient.updatePayment(payment);

            response.setStatus(HttpServletResponse.SC_CREATED);
        } catch (Exception e) {
            log.error("Error processing the webhook", e);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }

    }

}