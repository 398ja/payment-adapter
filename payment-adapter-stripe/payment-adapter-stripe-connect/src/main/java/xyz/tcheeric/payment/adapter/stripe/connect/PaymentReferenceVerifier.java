package xyz.tcheeric.payment.adapter.stripe.connect;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification;

import static xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification.MISMATCH;
import static xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification.NOT_CONNECTED;
import static xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification.NOT_FOUND;
import static xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification.NOT_SUCCEEDED;
import static xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification.VERIFIED;

/**
 * Answers "is this card reference a genuine payment to MY connected account?" for a stall.
 *
 * <p>The reference on a card coupon's issuance warrant is the purchase's PaymentIntent id
 * ({@code pi_}), copied from {@link StripePurchase#getPaymentIntentId()} by the Purchase Issuer. A
 * warrant is signed, so the reference was not edited after issuance, but a signature says nothing
 * about whether the payment exists. This does, by asking Stripe.
 *
 * <p>Nothing the caller says is trusted except which reference to look at. The connected account is
 * the stall's own, looked up by its authenticated pubkey, and the PaymentIntent is read with that
 * account as {@code Stripe-Account}, so a reference from another stall, or from the platform,
 * answers {@code not_found} exactly as a made-up one does. The amount and currency it must match are
 * this adapter's own purchase row, not the browser's.
 *
 * <p>No coupon id is matched. One purchase may be split into several coupons that all carry the
 * same {@code pi_}, so "this reference issued a different coupon" would be a false alarm.
 *
 * <p>Verdicts are cached. A succeeded PaymentIntent never changes its amount, currency or owner, and
 * a reference that is not on the account never will be. Refunds and disputes are a different
 * question (payment-adapter#265) and are not answered here.
 */
public class PaymentReferenceVerifier {

    /** Bounded so a client asking about random ids cannot grow it without limit. */
    static final int CACHE_LIMIT = 10_000;

    private final ConnectedStripeAccountRepository accounts;
    private final StripePurchaseRepository purchases;
    private final StripeConnectClient stripe;
    private final Map<String, PaymentReferenceVerification> cache = new ConcurrentHashMap<>();

    public PaymentReferenceVerifier(ConnectedStripeAccountRepository accounts,
                                    StripePurchaseRepository purchases,
                                    StripeConnectClient stripe) {
        this.accounts = accounts;
        this.purchases = purchases;
        this.stripe = stripe;
    }

    /**
     * @param merchantPubkey the authenticated stall, never taken from the request body
     * @param reference      the {@code pi_} id on the coupon
     * @throws xyz.tcheeric.payment.adapter.stripe.connect.exception.StripeConnectException when
     *                       Stripe cannot be asked, so the caller shows "unknown", never "fake"
     */
    public PaymentReferenceVerification verify(String merchantPubkey, String reference) {
        if (reference == null || !reference.matches("pi_[A-Za-z0-9_]{1,250}")) {
            return PaymentReferenceVerification.of(NOT_FOUND, reference, "not a PaymentIntent id");
        }
        Optional<ConnectedStripeAccount> account = accounts.findByMerchantPubkey(merchantPubkey);
        if (account.isEmpty() || account.get().getStripeAccountId() == null) {
            return PaymentReferenceVerification.of(NOT_CONNECTED, reference, "no connected account");
        }
        String accountId = account.get().getStripeAccountId();
        String key = accountId + "|" + reference;
        PaymentReferenceVerification cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        PaymentReferenceVerification answer = decide(accountId, reference);
        if (answer.isFinal() && cache.size() < CACHE_LIMIT) {
            cache.put(key, answer);
        }
        return answer;
    }

    private PaymentReferenceVerification decide(String accountId, String reference) {
        Optional<PaymentIntentSnapshot> found = stripe.retrievePaymentIntent(accountId, reference);
        if (found.isEmpty() || !reference.equals(found.get().id())) {
            return PaymentReferenceVerification.of(NOT_FOUND, reference,
                    "no such payment on your Stripe account");
        }
        PaymentIntentSnapshot intent = found.get();
        if (!"succeeded".equals(intent.status())) {
            return PaymentReferenceVerification.of(NOT_SUCCEEDED, reference, "payment " + intent.status());
        }
        Optional<StripePurchase> row = purchases.findByPaymentIntentId(reference);
        if (row.isEmpty()) {
            return PaymentReferenceVerification.of(MISMATCH, reference,
                    "a payment on your account, but no Imani purchase names it");
        }
        StripePurchase purchase = row.get();
        if (purchase.getConnectedAccountId() != null && !accountId.equals(purchase.getConnectedAccountId())) {
            return PaymentReferenceVerification.of(MISMATCH, reference, "purchase recorded for another account");
        }
        if (purchase.getAmountMinor() == null || purchase.getAmountMinor() != intent.amount()) {
            return PaymentReferenceVerification.of(MISMATCH, reference, "amount differs from the purchase");
        }
        if (!lower(purchase.getCurrency()).equals(lower(intent.currency()))) {
            return PaymentReferenceVerification.of(MISMATCH, reference, "currency differs from the purchase");
        }
        return new PaymentReferenceVerification(VERIFIED, reference, intent.amount(), lower(intent.currency()),
                intent.livemode(), null);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
