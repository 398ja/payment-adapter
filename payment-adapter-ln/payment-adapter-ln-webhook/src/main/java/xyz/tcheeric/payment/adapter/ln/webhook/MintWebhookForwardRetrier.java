package xyz.tcheeric.payment.adapter.ln.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.reconcile.MintForwardRetrier;
import xyz.tcheeric.payment.adapter.webhook.forwarder.MintWebhookForwarder;
import xyz.tcheeric.payment.adapter.webhook.forwarder.PaymentNotification;

/**
 * Re-sends a settled payment through the same forwarder the inline webhook path uses
 * (398ja/cashu-mint#462).
 *
 * <p>Sharing the forwarder is the point rather than an economy. Two implementations of "tell the
 * mint a payment arrived" would eventually disagree about what counts as delivered, and the
 * failure mode of that disagreement is a payment recorded as forwarded that never was — the
 * exact state this whole change exists to make impossible.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MintWebhookForwardRetrier implements MintForwardRetrier {

    private final MintWebhookForwarder forwarder;

    @Override
    public boolean retryForward(GatewayQuote quote) {
        if (forwarder == null || !forwarder.isEnabled()) {
            return false;
        }
        log.info("mint_forward_retry quote_id={} amount={} — re-delivering a settled payment",
                quote.getQuoteId(), quote.getAmount());
        // The preimage is not persisted on the quote, so the re-delivery carries none. The mint
        // binds the payment on (provider, provider_event_id) and the amount, not the preimage,
        // which is why this can be reconstructed at all. If that ever stops being true, this is
        // the line that has to change rather than the reconciler.
        return forwarder.notifyPaymentReceived(
                PaymentNotification.forBolt11(quote.getQuoteId(), quote.getAmount(), null));
    }
}
