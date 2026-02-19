package xyz.tcheeric.payment.adapter.webhook.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookUnknownTypeException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookHandler;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookPayload;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

import java.time.Instant;

/**
 * Dispatches webhook requests to the appropriate handler based on payment type.
 */
@Slf4j
public class WebhookDispatcher {

    private final WebhookRegistry registry;
    private final PaymentClient paymentClient;

    public WebhookDispatcher() {
        this(WebhookRegistry.getInstance(), new PaymentClient());
    }

    public WebhookDispatcher(WebhookRegistry registry, PaymentClient paymentClient) {
        this.registry = registry;
        this.paymentClient = paymentClient;
    }

    /**
     * Dispatches a webhook request to the appropriate handler.
     *
     * @param paymentType the payment type (extracted from URL path)
     * @param request     the HTTP request
     * @return response containing status and result
     */
    @SuppressWarnings("unchecked")
    public WebhookResponse dispatch(String paymentType, HttpServletRequest request) {
        log.info("Dispatching webhook: paymentType={}", paymentType);

        WebhookHandler<WebhookPayload> handler = (WebhookHandler<WebhookPayload>) registry
                .getHandler(paymentType)
                .orElseThrow(() -> new WebhookUnknownTypeException(paymentType));

        try {
            // 1. Parse payload
            WebhookPayload payload = handler.parsePayload(request);
            log.debug("Parsed payload: idempotencyKey={}, eventType={}",
                    payload.getIdempotencyKey(), payload.getEventType());

            // 2. Validate signature
            handler.validateSignature(payload, request);

            // 3. Process webhook
            WebhookResult result = handler.handle(payload);
            log.info("Webhook processed: paymentType={}, paymentId={}, processed={}, newState={}",
                    paymentType, result.paymentId(), result.processed(), result.newState());

            // 4. Update payment state if processed
            if (result.processed() && result.paymentId() != null) {
                updatePaymentState(result);
            }

            return WebhookResponse.success(result);

        } catch (WebhookDuplicateException e) {
            log.info("Duplicate webhook ignored: key={}", e.getIdempotencyKey());
            return WebhookResponse.duplicate(e.getIdempotencyKey());

        } catch (WebhookException e) {
            log.error("Webhook processing failed: type={}, error={}", paymentType, e.getMessage());
            return WebhookResponse.error(handler.getErrorStatusCode(e), e.getMessage());

        } catch (Exception e) {
            log.error("Unexpected error processing webhook: type={}", paymentType, e);
            return WebhookResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Updates the payment state after successful webhook processing.
     */
    private void updatePaymentState(WebhookResult result) {
        try {
            GatewayPayment payment = paymentClient.getByPaymentId(result.paymentId());
            if (payment != null) {
                payment.setState(result.newState());
                payment.setConfirmedDate(Instant.now());
                paymentClient.updatePayment(payment);
                log.debug("Updated payment state: paymentId={}, newState={}",
                        result.paymentId(), result.newState());
            } else {
                log.warn("Payment not found for update: paymentId={}", result.paymentId());
            }
        } catch (Exception e) {
            log.error("Failed to update payment state: paymentId={}", result.paymentId(), e);
        }
    }
}
