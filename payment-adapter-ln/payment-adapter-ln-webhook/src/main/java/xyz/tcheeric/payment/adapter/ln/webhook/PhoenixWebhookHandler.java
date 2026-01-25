package xyz.tcheeric.payment.adapter.ln.webhook;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.Direction;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookParseException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookHandler;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

/**
 * Webhook handler for Phoenixd Lightning node webhooks.
 * Processes payment_received events from phoenixd.
 */
@Slf4j
public class PhoenixWebhookHandler implements WebhookHandler<PhoenixWebhookPayload> {

    private static final String PAYMENT_TYPE = "phoenixd";
    private static final String EVENT_PAYMENT_RECEIVED = "payment_received";

    private final QuoteClient quoteClient;
    private final PaymentClient paymentClient;

    public PhoenixWebhookHandler() {
        this(new QuoteClient(), new PaymentClient());
    }

    public PhoenixWebhookHandler(QuoteClient quoteClient, PaymentClient paymentClient) {
        this.quoteClient = quoteClient;
        this.paymentClient = paymentClient;
    }

    @Override
    public String getPaymentType() {
        return PAYMENT_TYPE;
    }

    @Override
    public PhoenixWebhookPayload parsePayload(HttpServletRequest request) throws WebhookParseException {
        String type = request.getParameter("type");
        String amountStr = request.getParameter("amountSat");
        String paymentHash = request.getParameter("paymentHash");
        String externalId = request.getParameter("externalId");

        if (externalId == null || externalId.isBlank()) {
            throw new WebhookParseException("Missing required parameter: externalId");
        }

        Integer amount = null;
        if (amountStr != null && !amountStr.isBlank()) {
            try {
                amount = Integer.parseInt(amountStr);
            } catch (NumberFormatException e) {
                throw new WebhookParseException("Invalid amountSat: must be an integer", e);
            }
        }

        log.debug("Parsed phoenixd webhook: type={}, amount={}, paymentHash={}, externalId={}",
                type, amount, paymentHash, externalId);

        return new PhoenixWebhookPayload(type, amount, paymentHash, externalId);
    }

    @Override
    public WebhookResult handle(PhoenixWebhookPayload payload) throws WebhookProcessingException, WebhookDuplicateException {
        log.info("Processing phoenixd webhook: externalId={}, type={}",
                payload.externalId(), payload.type());

        // 1. Find the quote by externalId (invoice ID)
        GatewayQuote quote = quoteClient.getByInvoiceId(payload.externalId());
        if (quote == null) {
            throw new WebhookProcessingException("Quote not found: " + payload.externalId());
        }

        // 2. Validate quote direction
        if (quote.getDirection() != Direction.RECEIVE) {
            throw new WebhookProcessingException("Invalid quote direction: " + quote.getDirection());
        }

        // 3. Find the payment
        GatewayPayment payment = paymentClient.getByQuoteId(quote.getQuoteId());
        if (payment == null) {
            throw new WebhookProcessingException("Payment not found for quote: " + quote.getQuoteId());
        }

        // 4. Check for duplicate (already confirmed)
        if (payment.getState() == State.CONFIRMED) {
            log.info("Payment already confirmed: paymentId={}", payment.getPaymentId());
            return WebhookResult.duplicate(payment.getPaymentId(), State.CONFIRMED);
        }

        // 5. Validate payment hash if provided
        if (payload.paymentHash() != null && payment.getPaymentHash() != null
                && !payload.paymentHash().equals(payment.getPaymentHash())) {
            throw new WebhookProcessingException("Payment hash mismatch: expected="
                    + payment.getPaymentHash() + ", received=" + payload.paymentHash());
        }

        // 6. Validate amount if provided
        if (payload.amountSat() != null && payment.getAmount() != null
                && !payload.amountSat().equals(payment.getAmount())) {
            throw new WebhookProcessingException("Amount mismatch: expected="
                    + payment.getAmount() + ", received=" + payload.amountSat());
        }

        // 7. Validate state transition (allow PENDING or PAID -> CONFIRMED)
        State currentState = payment.getState();
        if (currentState != null && currentState != State.PENDING && currentState != State.PAID) {
            throw new WebhookProcessingException("Invalid state for confirmation: " + currentState);
        }

        // 8. Validate event type if provided
        if (payload.type() != null && !EVENT_PAYMENT_RECEIVED.equals(payload.type())) {
            throw new WebhookProcessingException("Unsupported event type: " + payload.type());
        }

        log.info("Payment confirmed: paymentId={}, quoteId={}",
                payment.getPaymentId(), quote.getQuoteId());

        return WebhookResult.success(payment.getPaymentId(), State.CONFIRMED);
    }
}
