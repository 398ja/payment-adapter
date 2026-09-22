package xyz.tcheeric.payment.adapter.webhook.core;

import org.springframework.web.client.HttpClientErrorException;
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
     *
     * <p>A missing {@code payment} row is NOT an error here. Spring Data REST answers 404
     * for an empty {@code Optional}, so {@code getByPaymentId} throws rather than returning
     * null, and the null branch below is unreachable in practice. On a deployment that does
     * not populate the {@code payment} table at all — staging is one, with zero rows — that
     * made every single webhook log a stack trace at ERROR for a condition that is normal
     * and harmless. The quote's own state is advanced elsewhere and does not depend on this.
     *
     * <p>That noise is not free: three of these fired during the #462 investigation and had
     * to be ruled out before the real defect could be seen. An ERROR that is always present
     * is an ERROR nobody reads.
     */
    private void updatePaymentState(WebhookResult result) {
        final GatewayPayment payment;
        try {
            payment = paymentClient.getByPaymentId(result.paymentId());
        } catch (HttpClientErrorException.NotFound e) {
            // Absent row: normal, and the reason this catch exists at all.
            log.debug("No payment row to update: paymentId={}", result.paymentId());
            return;
        } catch (Exception e) {
            log.error("Failed to look up payment: paymentId={}", result.paymentId(), e);
            return;
        }

        if (payment == null) {
            log.debug("No payment row to update: paymentId={}", result.paymentId());
            return;
        }

        // The write is deliberately OUTSIDE the NotFound catch above.
        //
        // Both calls used to share one try block, so a 404 from updatePayment — a row deleted
        // between the read and the write, or a client pointed at the wrong path — was logged
        // at debug and discarded, indistinguishable from the absent-row case that is normal.
        // A failed read means there is nothing to do; a failed WRITE means a state transition
        // was lost. Quietening the first must not quieten the second.
        try {
            payment.setState(result.newState());
            payment.setConfirmedDate(Instant.now());
            paymentClient.updatePayment(payment);
            log.debug("Updated payment state: paymentId={}, newState={}",
                    result.paymentId(), result.newState());
        } catch (Exception e) {
            log.error("Failed to update payment state: paymentId={} newState={}",
                    result.paymentId(), result.newState(), e);
        }
    }
}
