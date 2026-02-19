package xyz.tcheeric.payment.adapter.webhook.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.webhook.core.WebhookDispatcher;
import xyz.tcheeric.payment.adapter.webhook.core.WebhookResponse;

import java.io.IOException;

/**
 * Servlet that dispatches webhook requests to the appropriate handler.
 * Routes requests based on the payment type in the URL path.
 *
 * <p>URL pattern: /webhook/{paymentType}
 * <ul>
 *     <li>/webhook/phoenixd - Phoenixd Lightning webhooks</li>
 *     <li>/webhook/stripe - Stripe webhooks (future)</li>
 *     <li>/webhook/cash - Cash webhooks (future)</li>
 * </ul>
 */
@WebServlet(name = "WebhookDispatcher", urlPatterns = "/webhook/*")
@Slf4j
public class WebhookDispatcherServlet extends HttpServlet {

    private WebhookDispatcher dispatcher;
    private ObjectMapper objectMapper;

    @Override
    public void init() {
        dispatcher = new WebhookDispatcher();
        objectMapper = new ObjectMapper();
        log.info("WebhookDispatcherServlet initialized");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        // Health check endpoint
        response.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String paymentType = extractPaymentType(request.getPathInfo());

        if (paymentType == null || paymentType.isBlank()) {
            log.warn("Missing payment type in webhook URL: {}", request.getRequestURI());
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing payment type in URL path");
            return;
        }

        log.info("Received webhook request: paymentType={}, remoteAddr={}",
                paymentType, request.getRemoteAddr());

        WebhookResponse result = dispatcher.dispatch(paymentType, request);

        response.setStatus(result.statusCode());
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), result.toMap());
    }

    /**
     * Extracts the payment type from the URL path.
     * /webhook/phoenixd -> "phoenixd"
     * /webhook/phoenixd/extra -> "phoenixd"
     */
    private String extractPaymentType(String pathInfo) {
        if (pathInfo == null || pathInfo.length() <= 1) {
            return null;
        }
        // Remove leading slash and get first segment
        String path = pathInfo.substring(1);
        int slashIndex = path.indexOf('/');
        return slashIndex > 0 ? path.substring(0, slashIndex) : path;
    }

    private void sendError(HttpServletResponse response, int statusCode, String message) throws IOException {
        response.setStatus(statusCode);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(false, message));
    }

    private record ErrorResponse(boolean success, String message) {}
}
