package xyz.tcheeric.payment.adapter.ln.webhook;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.Direction;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookDuplicateException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookParseException;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.forwarder.MintWebhookForwarder;
import xyz.tcheeric.payment.adapter.webhook.forwarder.PaymentNotification;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookHandler;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

import java.time.Instant;

/**
 * Webhook handler for Phoenixd Lightning node webhooks.
 * Processes payment_received events from phoenixd.
 *
 * <p>When a payment is confirmed, optionally forwards the notification
 * to cashu-mint via the MintWebhookForwarder.
 */
@Slf4j
public class PhoenixWebhookHandler implements WebhookHandler<PhoenixWebhookPayload> {

    private static final String PAYMENT_TYPE = "phoenixd";
    private static final String EVENT_PAYMENT_RECEIVED = "payment_received";

    private final QuoteClient quoteClient;
    private final PaymentClient paymentClient;
    private final MintWebhookForwarder mintForwarder;

    public PhoenixWebhookHandler() {
        this(new QuoteClient(), new PaymentClient(), null);
    }

    public PhoenixWebhookHandler(QuoteClient quoteClient, PaymentClient paymentClient) {
        this(quoteClient, paymentClient, null);
    }

    public PhoenixWebhookHandler(QuoteClient quoteClient, PaymentClient paymentClient,
                                  MintWebhookForwarder mintForwarder) {
        this.quoteClient = quoteClient;
        this.paymentClient = paymentClient;
        this.mintForwarder = mintForwarder;
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

        // 3. Find the payment.
        //
        // A GatewayPayment only exists for money going OUT: PhoenixdGateway.pay() creates it
        // when the gateway pays an invoice. A RECEIVE quote is money coming IN — someone pays
        // the invoice we issued — and nothing creates a payment row for it, by design. Demanding
        // one here made this handler work only for melts, and left every mint webhook failing on
        // "Payment not found" with the quote sitting PAID (staging 2026-08-29: the mint never
        // learned of the payment, so issuance failed with funding_required).
        //
        // For a RECEIVE quote the quote IS the record of the payment, so it is what gets
        // validated and forwarded.
        // Absence arrives as a 404 from the REST client, not as null, so it has to be caught
        // rather than tested for.
        GatewayPayment payment;
        try {
            payment = paymentClient.getByQuoteId(quote.getQuoteId());
        } catch (HttpClientErrorException.NotFound absent) {
            payment = null;
        }
        if (payment == null) {
            return confirmIncomingPayment(quote, payload);
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

        forwardToMint(quote, payload);

        return WebhookResult.success(payment.getPaymentId(), State.CONFIRMED);
    }

    /**
     * Confirms a payment into a RECEIVE quote, which has no {@link GatewayPayment} of its own.
     *
     * <p>Applies the same checks the payment path applies, against the quote: the amount must be
     * the amount invoiced, and the event must be a payment notification. Then forwards to the
     * mint, which is the whole point of the call — without it the mint never records the funding
     * that lets it issue.
     */
    private WebhookResult confirmIncomingPayment(GatewayQuote quote, PhoenixWebhookPayload payload)
            throws WebhookProcessingException {

        if (payload.type() != null && !EVENT_PAYMENT_RECEIVED.equals(payload.type())) {
            throw new WebhookProcessingException("Unsupported event type: " + payload.type());
        }

        if (payload.amountSat() != null && quote.getAmount() != null
                && !payload.amountSat().equals(quote.getAmount())) {
            throw new WebhookProcessingException("Amount mismatch: expected="
                    + quote.getAmount() + ", received=" + payload.amountSat());
        }

        log.info("Incoming payment confirmed: quoteId={} amount={} state={}",
                quote.getQuoteId(), payload.amountSat(), quote.getState());

        forwardToMint(quote, payload);

        return WebhookResult.success(quote.getQuoteId(), State.CONFIRMED);
    }

    /**
     * Tells the mint a payment arrived, and records whether it worked.
     *
     * <p>Still best-effort towards phoenixd, and deliberately so: the payment has settled either
     * way, and failing this webhook would have phoenixd retry a payment that is already done.
     *
     * <p>What changed (398ja/cashu-mint#462) is that "best-effort" no longer means "and then
     * forget". The forwarder already retried three times and returned a boolean saying whether
     * the mint heard; that boolean was discarded, so a payment the mint never learned about was
     * indistinguishable from one it did. Seven such quotes accumulated on staging — money taken,
     * nothing issued, no row anywhere recording the gap.
     *
     * <p>A successful forward is now stamped on the quote. A failed one leaves
     * {@code mintNotifiedAt} null, which is what {@code PaidQuoteForwardReconciler} re-delivers
     * and {@code payment_adapter_paid_unforwarded} counts. The mint's webhook is idempotent on
     * {@code (provider, provider_event_id)}, so a later re-delivery of something that did arrive
     * is classified {@code duplicate} and costs nothing.
     */
    private void forwardToMint(GatewayQuote quote, PhoenixWebhookPayload payload) {
        String quoteId = quote.getQuoteId();
        if (mintForwarder == null || !mintForwarder.isEnabled()) {
            return;
        }
        try {
            boolean delivered = mintForwarder.notifyPaymentReceived(PaymentNotification.forBolt11(
                    quoteId, payload.amountSat(), payload.paymentHash()));
            if (delivered) {
                recordMintNotified(quote);
            } else {
                // The forwarder has already exhausted its retries and logged why. This line is
                // the one that says the money is now stranded until something sweeps it.
                log.error("[alert] mint_not_notified quote_id={} amount={} — payment settled and "
                                + "the mint was not told; left for PaidQuoteForwardReconciler",
                        quoteId, payload.amountSat());
            }
        } catch (RuntimeException e) {
            log.error("[alert] mint_not_notified quote_id={} — payment settled, forward threw; "
                    + "left for PaidQuoteForwardReconciler", quoteId, e);
        }
    }

    /**
     * Stamps the quote as forwarded.
     *
     * <p>A failure here is logged rather than thrown: the mint already has the payment, so the
     * worst case is that the reconciler re-delivers it later and the mint answers
     * {@code duplicate}. Losing the webhook response over a bookkeeping write would be the more
     * expensive mistake.
     */
    private void recordMintNotified(GatewayQuote quote) {
        try {
            quote.setMintNotifiedAt(Instant.now());
            quoteClient.updateQuote(quote);
        } catch (RuntimeException e) {
            log.warn("mint_notified_stamp_failed quote_id={} — the mint HAS the payment; a later "
                    + "sweep may re-deliver it and be answered duplicate", quote.getQuoteId(), e);
        }
    }

}
