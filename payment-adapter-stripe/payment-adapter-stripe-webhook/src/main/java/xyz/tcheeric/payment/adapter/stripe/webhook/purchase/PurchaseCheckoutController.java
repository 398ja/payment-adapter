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
@Slf4j
@RestController
@RequestMapping("/api/v1/checkout")
@RequiredArgsConstructor
public class PurchaseCheckoutController {

    private final ConnectedStripeAccountRepository accounts;
    private final StripeCheckoutService checkout;

    @PostMapping("/purchase")
    public ResponseEntity<?> start(@RequestBody PurchaseRequest request) {
        if (request == null || request.stallPubkey() == null || request.amountMinor() == null) {
            return ResponseEntity.badRequest().body(error("a stall and an amount are required"));
        }

        Optional<ConnectedStripeAccount> found =
                accounts.findByMerchantPubkey(request.stallPubkey().toLowerCase());

        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("this stall does not take card payments"));
        }

        ConnectedStripeAccount account = found.get();

        // The authoritative check, and the reason `acceptsCards` on the stall's
        // public record is allowed to be self-asserted: a stall that says it
        // takes cards but cannot be charged is refused here, and the customer
        // has lost one tap rather than a payment.
        if (!account.isChargesEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("this stall cannot accept card payments yet"));
        }

        // imani-woo ADR 0005's rule, checked at the last moment it is free to
        // check: a coupon cannot split across currencies, so a session priced
        // in one and a coupon issued in another cannot both be right.
        String currency = account.getDefaultCurrency();
        if (currency == null || currency.isBlank()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(error("this stall has no currency configured"));
        }

        String quoteId = UUID.randomUUID().toString();

        try {
            StripeCheckoutSession session = checkout.createPurchaseSession(
                    quoteId,
                    request.amountMinor(),
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
                    buyerMetadata(request.buyerPubkey()));

            return ResponseEntity.ok(new PurchaseResponse(session.getSessionUrl(), quoteId));
        } catch (RuntimeException e) {
            // Refusals from the gateway are the customer's problem to see now
            // rather than at the card form.
            log.warn("Could not start a purchase for stall {}: {}",
                    request.stallPubkey(), e.getMessage());
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    /**
     * A buyer, only when there is a valid one.
     *
     * <p>Absent means "no wallet yet", which is a legitimate purchase that
     * takes delivery by claim link rather than by DM. A malformed key is
     * treated the same way, because a coupon sent to nobody is worse than a
     * claim link.
     */
    private static Map<String, String> buyerMetadata(String buyerPubkey) {
        if (buyerPubkey == null) {
            return Map.of();
        }
        String trimmed = buyerPubkey.trim().toLowerCase();
        return trimmed.matches("^[0-9a-f]{64}$")
                ? Map.of("buyer_pubkey", trimmed)
                : Map.of();
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", message == null ? "unavailable" : message);
    }

    /**
     * @param stallPubkey whose coupon is being bought
     * @param amountMinor the face value, in the stall's own currency
     * @param buyerPubkey where to deliver, or null for a claim link
     */
    public record PurchaseRequest(String stallPubkey, Integer amountMinor, String buyerPubkey) {
    }

    /**
     * @param checkoutUrl where to send the customer to pay
     * @param reference   this purchase, for a caller that wants to follow it
     */
    public record PurchaseResponse(String checkoutUrl, String reference) {
    }
}
