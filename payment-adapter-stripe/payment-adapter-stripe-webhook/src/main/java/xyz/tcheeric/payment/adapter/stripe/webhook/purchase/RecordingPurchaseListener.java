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
     * Registers each new payment request's terms with gateway-core
     * (imani-gateway-core#131). Null in tests and wiring that predate it, which
     * simply skips registration.
     */
    private final GatewayFulfilmentClient fulfilment;

    public RecordingPurchaseListener(StripePurchaseRepository purchases, ConnectedStripeAccountRepository accounts) {
        this(purchases, accounts, null);
    }

    /** Where a gift's pinned delivery comes from (imani-wallet#410). Null where gifting is off. */
    private xyz.tcheeric.payment.adapter.core.model.repository.PurchaseDraftRepository drafts;

    public RecordingPurchaseListener withDrafts(xyz.tcheeric.payment.adapter.core.model.repository.PurchaseDraftRepository drafts) {
        this.drafts = drafts;
        return this;
    }

    /** Where the gifting draft id rides on the Checkout Session: the ONLY gift metadata Stripe sees. */
    static final String DRAFT_ID = "draft_id";

    /**
     * Copy the draft's PINNED delivery onto the debt (G-R1): who receives it was decided at review,
     * and nothing on the session, the return URL or a later handle change can alter it. A draft id
     * that names no draft (or a different stall or amount) is recorded with no recipient, which
     * the issuer parks for a person rather than guesses at.
     */
    private void applyDraft(StripePurchase row, PaidPurchase purchase) {
        String raw = purchase.metadata().get(DRAFT_ID);
        if (raw == null || drafts == null) {
            return;
        }
        java.util.UUID id;
        try {
            id = java.util.UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            row.setRecipientPubkey(null);
            return;
        }
        var found = drafts.findById(id);
        row.setDraftId(id);
        if (found.isEmpty()) {
            log.warn("Purchase {} names gift draft {} which does not exist; recorded with no recipient", purchase.eventId(), id);
            row.setRecipientPubkey(null);
            return;
        }
        var d = found.get();
        if (!d.getAmountMinor().equals(purchase.amountMinor())
                || (row.getStallPubkey() != null && !row.getStallPubkey().equals(d.getStallPubkey()))) {
            log.warn("Purchase {} does not match gift draft {} (amount or stall); recorded with no recipient", purchase.eventId(), id);
            row.setRecipientPubkey(null);
            return;
        }
        row.setDeliveryKind(d.getDeliveryKind());
        row.setRecipientPubkey(xyz.tcheeric.payment.adapter.core.model.entity.gifting.PurchaseDraft.EMAIL.equals(d.getDeliveryKind())
                ? null : d.getRecipientPubkey());
        row.setRefundPubkey(d.getRefundPubkey());
        row.setRecipientEmail(d.getRecipientEmail());
        row.setSealedSecret(d.getSealedSecret());
        row.setGiftMessage(d.getMessage());
        row.setSenderName(d.getSenderName());
        row.setBuyerEmail(d.getBuyerEmail());
        if (d.getConsumedAt() == null) {
            d.setConsumedAt(Instant.now());
            // The draft no longer needs its secret: the debt holds it until delivery scrubs it.
            d.setSealedSecret(null);
            drafts.save(d);
        }
    }

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

        // The same PAYMENT under a different event id: completed-and-paid then
        // async_payment_succeeded for one session. One payment is one debt, so
        // the second announcement is acknowledged and records nothing (card
        // atomicity audit S1c; V14 makes the session and pi_ unique as well).
        if (purchase.checkoutSessionId() != null) {
            var existing = purchases.findByCheckoutSessionId(purchase.checkoutSessionId());
            if (existing.isPresent()) {
                log.info("Purchase for session {} already recorded as {}; ignoring event {}",
                        purchase.checkoutSessionId(), existing.get().getEventId(), purchase.eventId());
                return;
            }
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
        applyDraft(row, purchase);
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

        // After commit, not inside the transaction (payment-adapter#260
        // review, PA-5). The PUT can take the client timeout, which would hold
        // a DB connection that long, and a rollback after a 201 would leave a
        // binding for a row that never existed. The cost is a window of
        // milliseconds in which the committed row is visible on /owed before
        // it is registered. That is covered: discharge registers late on
        // "unregistered", and the id reaches nobody but the issuer before its
        // delivery DM, which comes after a mint.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            register(row);
                        }
                    });
        } else {
            register(row);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The owner's policy (#265): money back before any coupon exists means the row is
     * REFUNDED and is never minted. After issue, only the facts are recorded: what happens to an
     * issued coupon is still the owner's to decide. A dispute is recorded and changes no status.
     *
     * <p>Idempotent: amounts are Stripe's cumulative totals, so a redelivery writes the same
     * values again. A purchase we never recorded is logged, not refused: refusing would only
     * make Stripe retry an event nothing here can ever act on.
     */
    @Override
    @Transactional
    public void onPurchaseReversed(ReversedPurchase reversal) {
        var found = reversal.paymentIntentId() == null
                ? java.util.Optional.<StripePurchase>empty()
                : purchases.findByPaymentIntentId(reversal.paymentIntentId());
        if (found.isEmpty()) {
            log.warn("{} {} for payment intent {} on account {} matches no recorded purchase; acknowledged",
                    reversal.disputed() ? "Dispute" : "Refund", reversal.eventId(),
                    reversal.paymentIntentId(), reversal.connectedAccountId());
            return;
        }
        StripePurchase row = found.get();
        if (reversal.disputed()) {
            row.setDisputed(true);
        } else {
            // Stripe's amount_refunded is cumulative, so the larger figure is the truth whatever
            // order the events arrive in, and a redelivery changes nothing.
            long reported = reversal.refundedAmountMinor() != null
                    ? reversal.refundedAmountMinor() : reversal.amountMinor() == null ? 0L : reversal.amountMinor();
            long known = row.getRefundedAmountMinor() == null ? 0L : row.getRefundedAmountMinor();
            row.setRefundedAmountMinor(Math.max(known, reported));
            if (isUnissued(row)) {
                row.setStatus(StripePurchase.Status.REFUNDED);
                row.setClaimedUntil(null);
            }
        }
        row.setReversedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        purchases.save(row);
        log.info("Purchase {} {}: status {}, refunded {} of {}, disputed {}",
                row.getEventId(), reversal.disputed() ? "disputed" : "refunded", row.getStatus(),
                row.getRefundedAmountMinor(), row.getAmountMinor(), row.isDisputed());
    }

    /**
     * No coupon exists yet. ISSUING counts: a mint may be in flight, and if it lands the
     * discharge records its voucher without closing the row
     * ({@link PurchaseDischargeService}). BLOCKED counts too: nothing was minted.
     */
    private static boolean isUnissued(StripePurchase row) {
        return row.getVoucherId() == null
                && (row.getStatus() == StripePurchase.Status.OWED
                        || row.getStatus() == StripePurchase.Status.ISSUING
                        || row.getStatus() == StripePurchase.Status.BLOCKED);
    }

    /**
     * Bind the new request to this purchase's terms, as soon as its id exists.
     *
     * <p>gateway-core#131 counts a send only when it went to the registered
     * recipient, of the registered issuer and unit, for at least the registered
     * amount. Registering as soon as the row commits, before the issuer has minted or
     * delivered anything, means nobody else can register it first with terms
     * of their own.
     *
     * <p>Best effort, and never fatal: the debt is already recorded. A gateway
     * from before #131 answers 404/405, which the client logs; an outage or a
     * purchase with no buyer key or no known stall is left for
     * {@link PurchaseDischargeService} to register at discharge.
     */
    private void register(StripePurchase row) {
        if (fulfilment == null || row.getRecipientPubkey() == null || row.getStallPubkey() == null
                || row.getAmountMinor() == null || row.getCurrency() == null) {
            return;
        }
        try {
            GatewayFulfilmentClient.Registration outcome = fulfilment.register(
                    row.getPaymentRequestId(), row.getRecipientPubkey(), row.getStallPubkey(),
                    row.getAmountMinor(), PurchaseDischargeService.unitOf(row.getCurrency()));
            log.info("Payment request for purchase {}: {}", row.getEventId(), outcome);
        } catch (RuntimeException e) {
            log.warn("Could not register the payment request for purchase {}: {}", row.getEventId(), e.getMessage());
        }
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
