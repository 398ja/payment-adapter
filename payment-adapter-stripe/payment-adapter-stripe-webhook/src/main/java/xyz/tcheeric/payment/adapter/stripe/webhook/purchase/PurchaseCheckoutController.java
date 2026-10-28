package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Reason;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Source;
import xyz.tcheeric.payment.adapter.core.common.metrics.PaymentMetrics.Status;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;

/**
 * Starting a card purchase.
 *
 * <p>A shopper picks an amount and this returns somewhere to pay. Everything
 * that could refuse the purchase is checked HERE, before the customer sees a
 * card form, because the alternative is a payment page that fails after they
 * have committed.
 *
 * <h2>Public, unlike the rest of the purchase surface</h2>
 *
 * <p>{@code /api/v1/purchases} is behind a bearer token because it lists every
 * stall's debts. This is the opposite: it is reached from a QR on a stall sign
 * by somebody with no wallet and no session, which is the whole point of the
 * hosted purchase page.
 *
 * <p>What stops it being abused is that it creates a Checkout Session and
 * nothing else. A caller can make sessions for a stall that already agreed to
 * accept cards, and each one is an invitation to give that stall money.
 */
/*
 * Conditional on Stripe being enabled, exactly as StripeGatewayConfig is.
 *
 * Without this the controller is created unconditionally and asks for a
 * StripeCheckoutService that only exists when stripe.enabled=true, so the whole
 * application context fails to start on any deployment with Stripe off. A
 * purchase endpoint that cannot work should be absent, not fatal.
 */
@Slf4j
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "stripe", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/checkout")
@RequiredArgsConstructor
public class PurchaseCheckoutController {

    private final ConnectedStripeAccountRepository accounts;
    private final StripeCheckoutService checkout;
    private final StallReadinessClient readiness;

    /**
     * The most a single card purchase may be.
     *
     * <p>A bound here rather than only in {@code StripeCheckoutService}, whose
     * {@code maxAmountMinor} defaults to {@code Integer.MAX_VALUE} — no bound at
     * all. This endpoint is PUBLIC and unauthenticated, so without a ceiling
     * anyone could create sessions for arbitrary sums against a real stall.
     * That is not a theft, since paying one funds the stall, but it is a
     * plausible way to embarrass a merchant or trip their fraud controls.
     *
     * <p>Configurable, because "the most anyone would put on one voucher"
     * differs by market, and 1,000,000 minor units is a starting point rather
     * than a truth.
     */
    @org.springframework.beans.factory.annotation.Value("${purchase.max-amount-minor:1000000}")
    private long maxAmountMinor;

    /** imani-deploy#210. A setter, not a constructor argument, so existing wiring is unchanged. */
    private PaymentMetrics metrics = new PaymentMetrics();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry registry) {
        this.metrics = new PaymentMetrics(registry).preRegister(Source.CARD, Status.INITIATED, Status.REFUSED);
    }

    /** One refused checkout. No money moved, so no amount and, often, no currency yet. */
    private ResponseEntity<?> refused(Reason reason, String currency, ResponseEntity<?> response) {
        metrics.record(Source.CARD, Status.REFUSED, reason, currency);
        return response;
    }

    /**
     * The gifting site's draft service (imani-wallet#410). Absent where gifting is not wired, in
     * which case a {@code draftId} body is refused rather than read as a wallet purchase.
     */
    private xyz.tcheeric.payment.adapter.stripe.webhook.gifting.GiftingService gifting;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setGifting(xyz.tcheeric.payment.adapter.stripe.webhook.gifting.GiftingService gifting) {
        this.gifting = gifting;
    }

    @PostMapping("/purchase")
    public ResponseEntity<?> start(@RequestBody PurchaseRequest request) {
        // A gift (#410): the draft id is the WHOLE request. Every other field is ignored, so
        // nothing sent with Pay can change the amount or who receives the voucher (G-R1).
        if (request != null && request.draftId() != null) {
            if (gifting == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("gifting is not available here"));
            }
            UUID id;
            try {
                id = UUID.fromString(request.draftId().trim());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(error("that is not a gift"));
            }
            var answer = gifting.pay(id);
            if (answer.status().is2xxSuccessful()) {
                metrics.record(Source.CARD, Status.INITIATED, Reason.NONE, null);
            } else {
                metrics.record(Source.CARD, Status.REFUSED, Reason.INVALID_REQUEST, null);
            }
            return ResponseEntity.status(answer.status()).body(answer.body());
        }
        if (request == null || request.stallPubkey() == null || request.amountMinor() == null) {
            return refused(Reason.INVALID_REQUEST, null, ResponseEntity.badRequest().body(error("a stall and an amount are required")));
        }

        // Checked before anything else touches Stripe. A negative or zero
        // amount is nonsense a customer cannot have meant, and an enormous one
        // is refused here rather than by a card network later.
        if (request.amountMinor() <= 0) {
            return refused(Reason.INVALID_REQUEST, null, ResponseEntity.badRequest().body(error("the amount must be more than zero")));
        }
        if (request.amountMinor() > maxAmountMinor) {
            return refused(Reason.INVALID_REQUEST, null, ResponseEntity.badRequest().body(error("that amount is too large for one voucher")));
        }

        // A pubkey shaped like a pubkey. Not an authorisation check — the
        // lookup below is — but it keeps a malformed value out of a database
        // query and out of the logs.
        if (!request.stallPubkey().trim().toLowerCase().matches("^[0-9a-f]{64}$")) {
            return refused(Reason.INVALID_REQUEST, null, ResponseEntity.badRequest().body(error("that is not a stall address")));
        }

        // A buyer with no wallet is refused HERE, before any Checkout Session
        // exists, so nothing can be charged (imani-wallet#267 item 2). Their
        // coupon would be delivered by Cashlink (claim link), and that delivery
        // is not wired yet: purchase-issuer would mint it and retry forever.
        // A malformed key is refused the same way rather than silently becoming
        // a guest, which is what buyerMetadata used to do.
        String buyer = normalisedBuyer(request.buyerPubkey());
        if (buyer == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(error(GUEST_REFUSED));
        }

        Optional<ConnectedStripeAccount> found =
                accounts.findByMerchantPubkey(request.stallPubkey().toLowerCase());

        if (found.isEmpty()) {
            return refused(Reason.NOT_FOUND, null, ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("this stall does not take card payments")));
        }

        ConnectedStripeAccount account = found.get();

        // The authoritative check, and the reason `acceptsCards` on the stall's
        // public record is allowed to be self-asserted: a stall that says it
        // takes cards but cannot be charged is refused here, and the customer
        // has lost one tap rather than a payment.
        if (!account.isChargesEnabled()) {
            return refused(Reason.CHARGES_DISABLED, account.getDefaultCurrency(), ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("this stall cannot accept card payments yet")));
        }

        // imani-woo ADR 0005's rule, checked at the last moment it is free to
        // check: a coupon cannot split across currencies, so a session priced
        // in one and a coupon issued in another cannot both be right.
        String currency = account.getDefaultCurrency();
        if (currency == null || currency.isBlank()) {
            return refused(Reason.CURRENCY_MISSING, null, ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("this stall has no currency configured")));
        }

        // payment-adapter#293: the last check before money can move, and the only one purchase-issuer
        // can answer: can a coupon actually be issued for this stall? The wallet greys Buy out for a
        // not-ready stall, but this endpoint is public, so a direct call must be refused too. Unknown
        // fails CLOSED: a buyer who pays for a coupon that cannot be issued needs a manual refund.
        StallReadinessClient.Readiness ready = readiness.check(request.stallPubkey().toLowerCase());
        if (ready.state() == StallReadinessClient.State.NOT_READY) {
            return refused(Reason.NOT_READY, currency, ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", STALL_NOT_READY, "reason", ready.reason())));
        }
        if (ready.state() != StallReadinessClient.State.READY) {
            return refused(Reason.READINESS_UNKNOWN, currency, ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(org.springframework.http.HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .body(error(CARD_SALES_UNAVAILABLE)));
        }

        String quoteId = UUID.randomUUID().toString();

        try {
            StripeCheckoutSession session = checkout.createPurchaseSession(
                    quoteId,
                    // Safe by the ceiling checked above, which is far below
                    // Integer.MAX_VALUE. The narrowing is explicit so a future
                    // change to that ceiling has to think about it.
                    Math.toIntExact(request.amountMinor()),
                    "Voucher",
                    currency,
                    account.getStripeAccountId(),
                    // No platform fee. Taking one is a commercial decision that
                    // belongs in configuration made deliberately, not in a
                    // constant chosen while writing a controller.
                    null,
                    // The buyer rides on the session and comes back on the
                    // webhook. Validated before the card is charged and never
                    // typed into a Stripe form: a mistyped key at the moment
                    // money moves is a coupon minted to nobody.
                    Map.of("buyer_pubkey", buyer));

            metrics.record(Source.CARD, Status.INITIATED, Reason.NONE, currency, request.amountMinor());
            return ResponseEntity.ok(new PurchaseResponse(session.getSessionUrl(), quoteId));
        } catch (xyz.tcheeric.payment.adapter.stripe.gateway.exception.StripeCheckoutCreationException e) {
            if (!e.isStripeUnavailable()) {
                return gatewayRefused(request, currency, e);
            }
            // payment-adapter#274: Stripe is down or slow. Not the buyer's mistake, and no session
            // exists, so a retry cannot charge twice. Answer fast and say when to come back.
            log.warn("Stripe unavailable starting a purchase for stall {}: {}",
                    request.stallPubkey(), e.getMessage());
            return refused(Reason.GATEWAY_ERROR, currency, ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(org.springframework.http.HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .body(error(CARD_PAYMENTS_UNAVAILABLE)));
        } catch (RuntimeException e) {
            return gatewayRefused(request, currency, e);
        }
    }

    /** What a buyer sees when Stripe is unavailable. */
    static final String CARD_PAYMENTS_UNAVAILABLE = "card payments are briefly unavailable, try again";

    static final String RETRY_AFTER_SECONDS = "5";

    /** What a buyer sees when purchase-issuer says the stall cannot be issued for (#293). */
    static final String STALL_NOT_READY = "this stall cannot sell vouchers by card right now";

    /** What a buyer sees when readiness could not be checked (#293). Fail closed. */
    static final String CARD_SALES_UNAVAILABLE = "card sales temporarily unavailable, try again shortly";

    /** Refusals from the gateway are the customer's to see now, rather than at the card form. */
    private ResponseEntity<?> gatewayRefused(PurchaseRequest request, String currency, RuntimeException e) {
        log.warn("Could not start a purchase for stall {}: {}", request.stallPubkey(), e.getMessage());
        return refused(Reason.GATEWAY_ERROR, currency, ResponseEntity.badRequest().body(error(e.getMessage())));
    }

    /** The words a guest sees. Shared with the wallet's Buy page. */
    static final String GUEST_REFUSED = "Create a wallet first to buy a voucher by card";

    /**
     * The buyer's key, lower-cased, or null when there is no valid one.
     *
     * <p>Null covers both "no wallet yet" and a malformed key. Either way the
     * coupon has nowhere to go until Cashlink delivery exists, so the caller
     * refuses the purchase.
     */
    private static String normalisedBuyer(String buyerPubkey) {
        if (buyerPubkey == null) {
            return null;
        }
        String trimmed = buyerPubkey.trim().toLowerCase();
        return trimmed.matches("^[0-9a-f]{64}$") ? trimmed : null;
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", message == null ? "unavailable" : message);
    }

    /**
     * @param stallPubkey whose coupon is being bought
     * @param amountMinor the face value, in the stall's own currency
     * @param buyerPubkey where to deliver. Required for this wallet body; guests buy through a draft.
     * @param draftId     a gifting Purchase Draft (imani-wallet#410); when present, the only field read
     */
    public record PurchaseRequest(String stallPubkey, Long amountMinor, String buyerPubkey, String draftId) {
        /** The wallet's Buy page body, unchanged. */
        public PurchaseRequest(String stallPubkey, Long amountMinor, String buyerPubkey) {
            this(stallPubkey, amountMinor, buyerPubkey, null);
        }
    }

    /**
     * @param checkoutUrl where to send the customer to pay
     * @param reference   this purchase, for a caller that wants to follow it
     */
    public record PurchaseResponse(String checkoutUrl, String reference) {
    }
}
