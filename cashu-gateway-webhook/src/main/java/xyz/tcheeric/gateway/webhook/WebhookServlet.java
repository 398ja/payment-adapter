package xyz.tcheeric.gateway.webhook;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.java.Log;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.enums.State;
import xyz.tcheeric.gateway.webhook.helper.validator.RequestValidatorFacade;

import java.util.logging.Level;

@WebServlet(name = "WebhookServlet", value = "/webhook")
@Log
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

            log.log(Level.INFO, "Confirming the payment: paymentId={0}", payment.getPaymentId());
            payment.setState(State.CONFIRMED);

            log.log(Level.FINE, "Updating the payment: payment={0}", payment);
            PaymentClient paymentClient = new PaymentClient();
            paymentClient.updatePayment(payment);

            response.setStatus(HttpServletResponse.SC_CREATED);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Error processing the webhook", e);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }

    }

    @Override
    public void destroy() {
    }
}