package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;

/**
 * Records a paid purchase as a debt, and does nothing else.
 *
 * <p>This is the whole of what {@code payment-adapter} does with a coupon
 * purchase. It writes one row and returns. Minting, delivering and discharging
 * belong to the issuer, which holds the keys this service must not.
 *
 * <h2>Why recording is enough to keep the promise</h2>
 *
 * <p>The contract the webhook makes is "paid means owed", not "paid means
 * delivered". A durable row satisfies it: from here the customer is owed a
 * coupon by a stall, that fact survives every process restart, and the worst
 * case is a coupon that arrives late rather than one that never existed.
 *
 * <p>Minting inline would break it rather than strengthen it. The mint can be
 * slow or down, a webhook that waits on it is one Stripe times out and
 * redelivers, and each redelivery is another chance to mint twice for one
 * payment.
 *
 * <h2>The buyer's pubkey, and why null is allowed</h2>
 *
 * <p>It rides on the session metadata, validated before the card was charged.
 * A purchase with none is not an error: that is a buyer with no wallet, who
 * takes delivery by claim link. Refusing here would refuse the customer the
 * hosted purchase page exists to serve.
 */
@Slf4j
@RequiredArgsConstructor
public class RecordingPurchaseListener implements StripePurchaseListener {

    /** Where the buyer's pubkey rides on the Checkout Session. */
    private static final String BUYER_PUBKEY = "buyer_pubkey";

    private final StripePurchaseRepository purchases;

    /**
     * Where a Stripe account id becomes the stall's Nostr pubkey.
     *
     * <p>Resolved HERE, while recording, rather than left for the issuer to
     * work out. The issuer holds no Stripe knowledge by design, and giving it
     * an {@code acct_…} to look a credential up by would find nothing: it
     * would fall back to manual issuance for every card sale and the feature
     * would silently do nothing.
     */
    private final ConnectedStripeAccountRepository accounts;

    /**
     * {@inheritDoc}
     *
     * <p>Transactional, because "recorded" has to mean committed. A row written
     * and then lost to a rollback would leave the webhook believing the debt
     * was safe.
     */
    @Override
    @Transactional
    public void onPurchasePaid(PaidPurchase purchase) {
        // Stripe redelivers. The unique constraint would catch it as an
        // exception; asking first makes it an ordinary outcome, and logs it as
        // one so a duplicate does not read like a fault.
        if (purchases.findByEventId(purchase.eventId()).isPresent()) {
            log.info("Purchase {} already recorded; ignoring redelivery", purchase.eventId());
            return;
        }

        StripePurchase row = new StripePurchase();
        row.setEventId(purchase.eventId());
        row.setCheckoutSessionId(purchase.checkoutSessionId());
        row.setPaymentIntentId(purchase.paymentIntentId());
        row.setConnectedAccountId(purchase.connectedAccountId());
        // Null when the account is unknown to us, which is a real possibility:
        // Connect delivers events for accounts we may have lost track of. The
        // debt is still recorded, and falls back to manual issuance.
        row.setStallPubkey(accounts.findByStripeAccountId(purchase.connectedAccountId())
                .map(account -> account.getMerchantPubkey())
                .orElse(null));
        row.setRecipientPubkey(normalisePubkey(purchase.metadata().get(BUYER_PUBKEY)));
        row.setAmountMinor(purchase.amountMinor());
        row.setCurrency(purchase.currency());
        row.setLivemode(purchase.livemode());
        row.setStatus(StripePurchase.Status.OWED);
        // Minted HERE, before any work happens, so a later discharge can be
        // checked against it rather than believed. An id the issuer chose at
        // delivery time would verify only that the issuer did something.
        row.setPaymentRequestId(newPaymentRequestId());
        row.setAttempts(0);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());

        purchases.save(row);

        log.info("Recorded purchase {} owed by account {} ({} {})",
                purchase.eventId(), purchase.connectedAccountId(),
                purchase.amountMinor(), purchase.currency());
    }

    /**
     * A pubkey worth trying to deliver to, or null.
     *
     * <p>Checked for shape rather than trusted, because metadata is a string
     * map and a malformed key here becomes a coupon sent to nobody. Null and
     * malformed collapse to the same answer deliberately: both mean "no DM
     * recipient", and the claim path serves both.
     */
    /**
     * An id the gateway's fulfilment check can be asked about.
     *
     * <p>128 bits from a secure source. The gateway treats possession of one as
     * evidence of having created it or been shown it, so guessability is the
     * whole security property — it refuses anything shorter than 16 characters
     * for exactly that reason.
     */
    private static String newPaymentRequestId() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static String normalisePubkey(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase();
        return trimmed.matches("^[0-9a-f]{64}$") ? trimmed : null;
    }
}
