package xyz.tcheeric.payment.adapter.webhook;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.webhook.core.WebhookDispatcher;
import xyz.tcheeric.payment.adapter.webhook.core.WebhookResponse;

/**
 * Legacy servlet for backward compatibility.
 * Delegates to the new WebhookDispatcher for phoenixd webhooks.
 *
 * @deprecated Use WebhookDispatcherServlet at /webhook/* instead
 */
@WebServlet(name = "WebhookServlet", value = "/webhook/phoenixd")
@Slf4j
@Deprecated
public class WebhookServlet extends HttpServlet {

    private WebhookDispatcher dispatcher;

    @Override
    public void init() {
        dispatcher = new WebhookDispatcher();
        log.info("Legacy WebhookServlet initialized (delegates to WebhookDispatcher)");
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) {
        try {
            WebhookResponse result = dispatcher.dispatch("phoenixd", request);
            response.setStatus(result.statusCode());
        } catch (Exception e) {
            log.error("Error processing the webhook", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
