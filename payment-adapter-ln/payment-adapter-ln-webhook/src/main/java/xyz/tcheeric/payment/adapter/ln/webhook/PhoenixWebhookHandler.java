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
     *
     * <p><strong>Stamps ONE field, and that is the whole point (#245).</strong> This used to call
     * {@code quoteClient.updateQuote(quote)}, which PUTs the entire object — including the
     * {@code state} this handler read back at the top of {@code handle} and has held ever since.
     * phoenixd marks the invoice paid in that window, so the PUT wrote back the stale
     * {@code PENDING} and silently reverted a settled payment. The mint then answered "Invoice
     * not paid" and three real staging sales died at the finalisation poll on 2026-09-23.
     *
     * <p>It was a race, not a constant failure, which is why it had gone unnoticed: a sale that
     * morning survived because its PATCH landed 23ms before this write, and the three that
     * afternoon lost by 0-2ms. The adapter's own logs recorded it writing {@code state=PAID}
     * once and {@code state=PENDING} three times.
     *
     * <p>A re-read before the PUT would only shrink that window. Naming one field CLOSES it:
     * this call has no opinion about {@code state} and can no longer express one. Payment state
     * belongs to whoever observed the payment; all this knows is that the mint was told.
     */
    private void recordMintNotified(GatewayQuote quote) {
        try {
            Instant notifiedAt = Instant.now();
            GatewayQuote stamped = quoteClient.stampMintNotified(quote.getId(), notifiedAt);

            // The response is CHECKED, not discarded.
            //
            // #245 was a write that reported success without landing, and the reason it cost an
            // afternoon is that every log line along the way said the opposite. Taking a 2xx as
            // proof here would rebuild that: a partial update the server accepts and does not
            // apply is exactly the shape of the original defect.
            //
            if (stamped == null || stamped.getMintNotifiedAt() == null) {
                log.error("[alert] mint_notified_stamp_lost quote_id={} — the stamp was accepted "
                        + "and did not land; the sweep will re-deliver and be answered duplicate",
                        quote.getQuoteId());
                return;
            }

            // The state check that is actually sound.
            //
            // Comparing the server's state against this handler's LOCAL copy would fire on every
            // healthy race: the local copy is stale by construction — that staleness IS #245 —
            // so a legitimate concurrent PAID reads as PENDING here and would look like a
            // regression. That alert would be lit routinely while naming a bug that was fixed,
            // which is the `payment_missing` mistake one layer down.
            //
            // The real invariant is directional and cannot false-positive: a payment that has
            // settled must not come back UNPAID. Only a write carrying `state` can do that, so
            // this catches a refactor that reintroduces the full-object PUT without accusing the
            // healthy path of anything.
            if (State.PAID.equals(quote.getState()) && !State.PAID.equals(stamped.getState())) {
                log.error("[alert] quote_unpaid_by_stamp quote_id={} before=PAID after={} — the "
                        + "mint-notified stamp reverted a settled payment; this is #245 again",
                        quote.getQuoteId(), stamped.getState());
            }

            // Keep the caller's copy consistent with what was just written, so anything reading
            // it further down this request sees the stamp rather than a stale null. Taken from
            // the SERVER's copy rather than the local Instant: if the two ever disagree, the
            // stored value is the one that matters.
            quote.setMintNotifiedAt(stamped.getMintNotifiedAt());
        } catch (RuntimeException e) {
            log.warn("mint_notified_stamp_failed quote_id={} — the mint HAS the payment; a later "
                    + "sweep may re-deliver it and be answered duplicate", quote.getQuoteId(), e);
        }
    }

}
