package xyz.tcheeric.payment.adapter.paypal.connect;

import static xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification.DISPUTED;
import static xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification.MISMATCH;
import static xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification.NOT_CONNECTED;
import static xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification.NOT_FOUND;
import static xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification.NOT_SUCCEEDED;
import static xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification.REFUNDED;
import static xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification.VERIFIED;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import xyz.tcheeric.payment.adapter.core.model.entity.paypal.ConnectedPaypalAccount;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedPaypalAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.paypal.connect.exception.PaypalConnectException;
import xyz.tcheeric.payment.adapter.paypal.gateway.exception.PaypalApiException;
import xyz.tcheeric.payment.adapter.paypal.gateway.model.PaypalCapture;
import xyz.tcheeric.payment.adapter.paypal.gateway.service.PaypalOrdersClient;
import xyz.tcheeric.payment.adapter.stripe.connect.model.PaymentReferenceVerification;

/**
 * "Is this card reference a genuine payment to MY PayPal account?": {@code PaymentReferenceVerifier}
 * for a PayPal capture id, with the same answers, the same precedence and the same JSON.
 *
 * <p>The capture is read as the stall's own seller ({@code PayPal-Auth-Assertion}), so a capture on
 * another seller answers {@code not_found} exactly as a made-up id does; and a capture whose payee
 * PayPal names is someone else is refused outright. Amount, currency and mode are compared with
 * this adapter's own PayPal ledger row, never the browser's. Refund and dispute state is that row's,
 * read on every request.
 *
 * <p>The JSON field is still {@code payment_intent_id}: the warrant's reference, under the name
 * every reader already parses. For a PayPal row it holds the capture id (ADR 0002).
 */
public class PaypalPaymentReferenceVerifier {

    static final int CACHE_LIMIT = 10_000;

    private final ConnectedPaypalAccountRepository accounts;
    private final StripePurchaseRepository purchases;
    private final PaypalOrdersClient paypal;
    private final boolean live;
    private final Map<String, Optional<PaypalCapture>> cache = new ConcurrentHashMap<>();

    public PaypalPaymentReferenceVerifier(ConnectedPaypalAccountRepository accounts, StripePurchaseRepository purchases,
            PaypalOrdersClient paypal, boolean live) {
        this.accounts = accounts;
        this.purchases = purchases;
        this.paypal = paypal;
        this.live = live;
    }

    /**
     * @throws PaypalConnectException when PayPal cannot be asked, so the caller shows "unknown"
     */
    public PaymentReferenceVerification verify(String merchantPubkey, String reference) {
        if (reference == null || !reference.matches("[A-Z0-9]{17}")) {
            return PaymentReferenceVerification.of(NOT_FOUND, reference, "not a PayPal capture id");
        }
        Optional<ConnectedPaypalAccount> account = accounts.findByMerchantPubkey(merchantPubkey);
        if (account.isEmpty() || account.get().getPaypalMerchantId() == null) {
            return PaymentReferenceVerification.of(NOT_CONNECTED, reference, "no connected PayPal account");
        }
        String merchantId = account.get().getPaypalMerchantId();
        return decide(merchantId, reference, paypalSide(merchantId, reference));
    }

    private Optional<PaypalCapture> paypalSide(String merchantId, String reference) {
        String key = merchantId + "|" + reference;
        Optional<PaypalCapture> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<PaypalCapture> found;
        try {
            found = Optional.of(paypal.getCapture(merchantId, reference))
                    .filter(capture -> reference.equals(capture.id()));
        } catch (PaypalApiException e) {
            if (e.isNotFound() || e.status() == 403) {
                found = Optional.empty();
            } else {
                throw PaypalConnectException.apiError("PayPal capture lookup failed: " + e.getMessage(), e);
            }
        }
        // PENDING may still complete; absent, or settled at some point, does not change.
        boolean stable = found.isEmpty() || found.get().succeeded();
        if (stable && cache.size() < CACHE_LIMIT) {
            cache.put(key, found);
        }
        return found;
    }

    private PaymentReferenceVerification decide(String merchantId, String reference, Optional<PaypalCapture> found) {
        if (found.isEmpty()) {
            return PaymentReferenceVerification.of(NOT_FOUND, reference, "no such payment on your PayPal account");
        }
        PaypalCapture capture = found.get();
        if (capture.payeeMerchantId() != null && !merchantId.equals(capture.payeeMerchantId())) {
            return PaymentReferenceVerification.of(NOT_FOUND, reference, "no such payment on your PayPal account");
        }
        if (!capture.succeeded()) {
            return PaymentReferenceVerification.of(NOT_SUCCEEDED, reference,
                    "payment " + lower(capture.status()));
        }
        Optional<StripePurchase> row = purchases.findByPaymentIntentId(reference)
                .filter(StripePurchase::isPaypal);
        if (row.isEmpty()) {
            return PaymentReferenceVerification.of(MISMATCH, reference,
                    "a payment on your account, but no Imani purchase names it");
        }
        StripePurchase purchase = row.get();
        if (purchase.getConnectedAccountId() != null && !merchantId.equals(purchase.getConnectedAccountId())) {
            return PaymentReferenceVerification.of(MISMATCH, reference, "purchase recorded for another account");
        }
        if (purchase.getAmountMinor() == null || capture.amountMinor() == null
                || !purchase.getAmountMinor().equals(capture.amountMinor())) {
            return PaymentReferenceVerification.of(MISMATCH, reference, "amount differs from the purchase");
        }
        if (!lower(purchase.getCurrency()).equals(lower(capture.currency()))) {
            return PaymentReferenceVerification.of(MISMATCH, reference, "currency differs from the purchase");
        }
        // A capture carries no mode of its own: it is live exactly when this deployment talks to live PayPal.
        if (purchase.isLivemode() != live) {
            return PaymentReferenceVerification.of(MISMATCH, reference, live
                    ? "a live payment, but the purchase was recorded in test mode"
                    : "a test mode payment, but the purchase was recorded as live");
        }
        String currency = lower(capture.currency());
        long amount = capture.amountMinor();
        if (purchase.isDisputed()) {
            return new PaymentReferenceVerification(DISPUTED, reference, amount, currency, live,
                    refunded(purchase), "the payment is disputed on PayPal");
        }
        if (refunded(purchase) != null) {
            return new PaymentReferenceVerification(REFUNDED, reference, amount, currency, live,
                    refunded(purchase), "the payment was refunded on PayPal");
        }
        return new PaymentReferenceVerification(VERIFIED, reference, amount, currency, live, null, null);
    }

    private static Long refunded(StripePurchase purchase) {
        Long minor = purchase.getRefundedAmountMinor();
        return minor != null && minor > 0 ? minor : null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
