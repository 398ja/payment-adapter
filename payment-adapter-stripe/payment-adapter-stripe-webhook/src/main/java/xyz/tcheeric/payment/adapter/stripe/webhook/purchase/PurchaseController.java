package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;

/**
 * What the issuer talks to.
 *
 * <p>Two questions and two answers: what is owed, and here is what happened.
 * Everything about coupons stays on the other side of this boundary — the
 * issuer mints and delivers, and reports back only enough for a debt to be
 * verified and closed.
 *
 * <h2>Guarded by {@link PurchaseApiTokenFilter}, and by not being published</h2>
 *
 * <p>These endpoints list every stall's outstanding sales and can mark debts
 * discharged. The token is in the repository; the other half of the protection
 * is a deployment not publishing this port, and neither is sufficient alone.
 *
 * <h2>Cross-stall by nature, which is the cost of central issuance</h2>
 *
 * <p>{@code GET /owed} answers for all stalls at once, because the issuer holds
 * every enrolled stall's credential and works one queue. Per-stall scoping here
 * would be theatre: the caller already holds the keys. That concentration is
 * the honest cost of a central issuer, counted once in {@code imani-wallet}
 * ADR 0009 rather than pretended away here.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/purchases")
@RequiredArgsConstructor
public class PurchaseController {

    /**
     * How many debts one call may return.
     *
     * <p>A bound rather than paging: the queue is drained repeatedly, so a
     * caller that keeps asking sees everything. An unbounded answer on a busy
     * day is a response nobody can hold in memory and a worker that starts by
     * doing nothing for a long time.
     */
    private static final int MAX_BATCH = 100;

    private final StripePurchaseRepository purchases;
    private final PurchaseDischargeService discharges;

    /** What is still owed, oldest first. */
    @GetMapping("/owed")
    public List<OwedPurchase> owed() {
        return purchases.findByStatusOrderByCreatedAtAsc(StripePurchase.Status.OWED).stream()
                .limit(MAX_BATCH)
                .map(OwedPurchase::of)
                .toList();
    }

    /**
     * Report that a coupon was issued for one purchase.
     *
     * <p>The verdict is not the caller's to decide. This asks the gateway
     * whether a completed send answered the payment request, so a discharge is
     * evidence rather than a claim, and the status codes say which happened:
     *
     * <ul>
     *   <li>200 — confirmed and closed.</li>
     *   <li>409 — the gateway sees no such send. The debt stands.</li>
     *   <li>503 — the gateway could not be asked. Try again; nothing changed.</li>
     * </ul>
     *
     * <p>503 rather than 500 for the unverifiable case, because it is a
     * retryable condition about a dependency rather than a fault in the
     * request. A caller that retries on 5xx does the right thing by accident,
     * and one that reads the code does it on purpose.
     */
    @PostMapping("/{eventId}/discharge")
    public ResponseEntity<Void> discharge(
            @PathVariable String eventId,
            @RequestBody DischargeRequest request) {

        if (request == null || request.voucherId() == null || request.voucherId().isBlank()) {
            // A discharge with no coupon named is one nobody could trace
            // afterwards, which defeats recording it at all.
            return ResponseEntity.badRequest().build();
        }

        PurchaseDischargeService.Result result =
                discharges.discharge(eventId, request.voucherId(), request.issuerId());

        return switch (result) {
            case DISCHARGED, ALREADY_DISCHARGED -> ResponseEntity.ok().build();
            case REFUSED -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case UNVERIFIABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            case UNKNOWN_PURCHASE -> ResponseEntity.notFound().build();
        };
    }

    /**
     * Report that an attempt failed, without closing anything.
     *
     * <p>Always 202: the report is accepted, and the debt is unchanged by
     * design. Answering 200 would suggest something was resolved.
     */
    @PostMapping("/{eventId}/failure")
    public ResponseEntity<Void> failure(
            @PathVariable String eventId,
            @RequestBody FailureRequest request) {
        discharges.recordFailure(eventId,
                request == null ? null : request.reason(),
                request != null && request.permanent(),
                // A failure that names a coupon is value that already exists,
                // and the debt must leave the mintable queue or the next pass
                // mints another one.
                request == null ? null : request.voucherId());
        return ResponseEntity.accepted().build();
    }

    /**
     * One debt, in the terms the issuer needs and nothing more.
     *
     * <p>No Stripe session id, no payment intent, no charge. The issuer mints
     * coupons; it has no business with the payment that funded them, and
     * handing it those ids would invite code that acts on them.
     *
     * @param eventId          the handle for reporting back
     * @param paymentRequestId stamped onto the send, so the discharge can be verified
     * @param connectedAccountId the stall that owes it
     * @param recipientPubkey  who to deliver to, or null for a claim link
     * @param livemode         false for test-mode: an issuer must refuse to mint real value
     */
    public record OwedPurchase(
            String eventId,
            String paymentRequestId,
            /**
             * The stall's NOSTR pubkey, which is what a credential is keyed by.
             * Null when we cannot identify the stall, which the issuer treats
             * as "issue by hand" rather than guessing.
             */
            String stallPubkey,
            String recipientPubkey,
            Long amountMinor,
            String currency,
            boolean livemode,
            int attempts) {

        static OwedPurchase of(StripePurchase purchase) {
            return new OwedPurchase(
                    purchase.getEventId(),
                    purchase.getPaymentRequestId(),
                    // NOT connectedAccountId. An acct_… addresses money and a
                    // pubkey addresses issuance, and the issuer needs the
                    // second. It is also deliberately the only stall identifier
                    // exposed: the issuer has no business with the Stripe
                    // account that funded the coupon.
                    purchase.getStallPubkey(),
                    purchase.getRecipientPubkey(),
                    purchase.getAmountMinor(),
                    purchase.getCurrency(),
                    purchase.isLivemode(),
                    purchase.getAttempts());
        }
    }

    /**
     * @param voucherId the coupon minted, required, kept as the trail
     * @param issuerId  who minted it, checked against what the gateway says
     */
    public record DischargeRequest(String voucherId, String issuerId) {
    }

    /**
    /**
     * @param reason    what went wrong, for whoever reads the row later
     * @param permanent true when retrying cannot help, e.g. a burnt credential
     * @param voucherId the coupon that WAS minted, when one was. Its presence
     *                  is what tells this service the debt must stop being
     *                  mintable, so omitting it after a successful mint is how
     *                  one payment becomes many coupons.
     */
    public record FailureRequest(String reason, boolean permanent, String voucherId) {
    }
}
