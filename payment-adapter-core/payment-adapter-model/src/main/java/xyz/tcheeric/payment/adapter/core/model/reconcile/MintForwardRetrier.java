package xyz.tcheeric.payment.adapter.core.model.reconcile;

import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;

/**
 * Re-sends one settled payment to the mint (398ja/cashu-mint#462).
 *
 * <p>A seam, for one reason: {@code PaidQuoteForwardReconciler} lives in
 * {@code payment-adapter-rest} alongside the repository it sweeps, while the forwarder that
 * knows how to talk to the mint lives in {@code payment-adapter-ln-webhook} — and {@code rest}
 * already depends on {@code ln-webhook}, so the reconciler cannot depend on the forwarder
 * without a cycle.
 *
 * <p>It therefore lives in {@code payment-adapter-model}, which both sides already depend on:
 * the reconciler calls the interface, the webhook module implements it, and the arrow keeps
 * pointing the way it already did.
 *
 * <p>It is deliberately narrower than {@code MintWebhookForwarder}: the reconciler has a quote
 * rather than a payment notification, and needs one question answered — did the mint accept it
 * this time? Everything else, including the retry policy and the HMAC signing, belongs to the
 * implementation and is shared with the inline path so the two cannot diverge on what counts as
 * delivered.
 */
public interface MintForwardRetrier {

    /**
     * Tries once more to tell the mint about a payment that already settled.
     *
     * <p>Implementations must be safe to call repeatedly on the same quote: the mint's webhook
     * is idempotent on {@code (provider, provider_event_id)}, so a payment that did arrive is
     * answered {@code duplicate} rather than funded twice.
     *
     * @param quote a {@code PAID} quote whose {@code mintNotifiedAt} is null
     * @return {@code true} if the mint accepted it, so the quote may be stamped
     */
    boolean retryForward(GatewayQuote quote);
}
