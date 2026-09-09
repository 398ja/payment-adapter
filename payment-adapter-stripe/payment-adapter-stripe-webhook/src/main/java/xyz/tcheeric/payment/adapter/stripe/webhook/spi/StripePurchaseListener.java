package xyz.tcheeric.payment.adapter.stripe.webhook.spi;

import java.util.Map;

/**
 * What follows a paid coupon purchase, decided outside this service.
 *
 * <p>A Stripe payment can mean more than one thing. Settling a mint quote is
 * one consequence and is hard-wired into {@code StripeWebhookHandler} because
 * it is this service's own business. A stall selling a coupon is a different
 * consequence of the same event, and it belongs to somebody else: issuing a
 * coupon needs custody of Nostr issuing keys, and a payments adapter is the
 * wrong place to acquire that.
 *
 * <p>So the handler says "this purchase was paid" and stops. What happens next
 * is an implementation of this interface, wired in by the deployment.
 *
 * <h2>What an implementation must guarantee</h2>
 *
 * <p><b>Record before acting.</b> The contract is "paid means owed": by the time
 * this returns normally, the obligation must be durable. Issuance may follow
 * later and may be retried; the debt may not be lost. Throwing means the record
 * did not happen, and the handler will mark the event failed so Stripe retries.
 *
 * <p><b>Be idempotent.</b> Stripe delivers more than once. The handler's
 * {@code ProcessedStripeWebhookEvent} table absorbs most repeats, but a
 * duplicate can still arrive before the first has been marked processed, and a
 * coupon cannot be un-minted.
 *
 * <p><b>Do not issue inline.</b> Minting a coupon means calling a gateway that
 * can be slow or down, and a webhook that waits for it is a webhook Stripe
 * times out and retries. Record the obligation, return, and discharge it
 * separately.
 *
 * <p>See {@code docs/adr/0001-coupon-purchases-are-direct-charges.md} and
 * {@code imani-wallet/docs/adr/0010-a-purchase-is-paid-before-it-is-issued.md}.
 */
public interface StripePurchaseListener {

    /**
     * A coupon purchase has been paid.
     *
     * @param purchase what was paid, by whom, and to which stall
     * @throws Exception to refuse the event, leaving it unprocessed for Stripe
     *                   to redeliver. Prefer throwing over swallowing: a lost
     *                   obligation is a customer who paid for nothing.
     */
    void onPurchasePaid(PaidPurchase purchase) throws Exception;

    /**
     * One paid purchase, in the terms the consequence needs.
     *
     * @param eventId          Stripe's event id, stable across redeliveries and
     *                         so the natural idempotency key
     * @param checkoutSessionId the session that was paid
     * @param paymentIntentId  the payment intent, where the event carries one
     * @param connectedAccountId the stall's {@code acct_…} that was charged.
     *                         Present because a purchase is a DIRECT charge; a
     *                         platform charge never reaches this listener
     * @param amountMinor      what the customer paid, in minor units
     * @param currency         the presentment currency
     * @param metadata         what the session carried, including the buyer's
     *                         pubkey. Validated before the card was charged
     * @param livemode         false for test-mode traffic, so a consequence can
     *                         refuse to mint real value from a test payment
     */
    record PaidPurchase(
            String eventId,
            String checkoutSessionId,
            String paymentIntentId,
            String connectedAccountId,
            Long amountMinor,
            String currency,
            Map<String, String> metadata,
            boolean livemode) {
    }
}
