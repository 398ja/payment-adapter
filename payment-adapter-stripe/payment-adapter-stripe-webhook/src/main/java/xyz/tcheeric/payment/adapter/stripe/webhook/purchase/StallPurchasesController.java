package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.gifting.Nip98Verifier;

/**
 * A stall's own card sales, so its wallet can file any sale record it missed (imani-wallet#275,
 * the pull on login, card atomicity U9).
 *
 * <p>{@code GET /api/v1/purchases?stall=<pubkey>}, read-only, two callers:
 *
 * <ul>
 *   <li><b>The stall</b>, with a NIP-98 {@code Authorization: Nostr ...} event signed BY that stall
 *       key for {@code GET <origin>/api/v1/purchases?stall=<pubkey>}, origins from
 *       {@code purchase.state.public-origins}. Another key is 403. {@link PurchaseApiTokenFilter}
 *       lets exactly this GET through without the bearer token, and nothing else under the
 *       prefix.</li>
 *   <li><b>The purchase issuer</b>, with its usual bearer token (admitted by the filter, which marks
 *       the request {@link PurchaseApiTokenFilter#ISSUER_ADMITTED}), so it can rebuild a missed
 *       sale record from what this service recorded rather than from what a wallet says.</li>
 * </ul>
 *
 * <p>Only purchases that minted a coupon (a voucher id is recorded), newest first, at most 200.
 * {@code paid_at} is when this service recorded the payment: the independent sale time the wallet
 * checks a record's own timestamp against (review residual R1). No Stripe account, session, buyer,
 * recipient or gift data.
 */
@RestController
public class StallPurchasesController {

    public static final String PATH = PurchaseApiTokenFilter.PROTECTED_PREFIX;
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    private final StripePurchaseRepository purchases;
    private final Nip98Verifier nip98;
    private final List<String> publicOrigins;

    @Autowired
    public StallPurchasesController(StripePurchaseRepository purchases,
            @Value("${purchase.state.public-origins:}") List<String> publicOrigins) {
        this(purchases, new Nip98Verifier(), publicOrigins);
    }

    public StallPurchasesController(StripePurchaseRepository purchases, Nip98Verifier nip98,
            List<String> publicOrigins) {
        this.purchases = purchases;
        this.nip98 = nip98;
        this.publicOrigins = publicOrigins == null ? List.of()
                : publicOrigins.stream().map(String::trim).filter(o -> !o.isEmpty())
                        .map(o -> o.replaceAll("/+$", "")).toList();
    }

    /**
     * @return 200 list, 400 malformed stall, 401 no or bad NIP-98, 403 signed by another key,
     *         503 no public origin configured (stall callers only)
     */
    @GetMapping(PATH)
    public ResponseEntity<?> byStall(@RequestParam(name = "stall", required = false) String stallParam,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest http) {
        String stall = stallParam == null ? "" : stallParam.toLowerCase(Locale.ROOT);
        if (!HEX64.matcher(stall).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "stall must be 64 hex characters"));
        }
        boolean issuer = http != null
                && Boolean.TRUE.equals(http.getAttribute(PurchaseApiTokenFilter.ISSUER_ADMITTED));
        if (!issuer) {
            if (publicOrigins.isEmpty()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "stall purchases are not configured"));
            }
            Optional<String> signer = signerFor(authorization, PATH + "?stall=" + stallParam);
            if (signer.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "NIP-98 authorization required"));
            }
            if (!signer.get().equalsIgnoreCase(stall)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "signed by another key"));
            }
        }
        return ResponseEntity.ok(purchases.findTop200ByStallPubkeyAndVoucherIdIsNotNullOrderByCreatedAtDesc(stall)
                .stream().map(StallPurchase::of).toList());
    }

    private Optional<String> signerFor(String authorization, String pathAndQuery) {
        for (String origin : publicOrigins) {
            Optional<String> signer = nip98.verify(authorization, origin + pathAndQuery, "GET", null);
            if (signer.isPresent()) {
                return signer;
            }
        }
        return Optional.empty();
    }

    /**
     * One card sale as the stall's ledger needs it.
     *
     * @param status  the purchase's own state ({@code DISCHARGED}, {@code ISSUED}, ...)
     * @param payment {@code PAID} | {@code PARTLY_REFUNDED} | {@code REFUNDED} | {@code DISPUTED},
     *                as the refund-state read words it
     * @param paid_at when this service recorded the payment (the webhook), independent of the issuer
     */
    public record StallPurchase(
            String voucher_id,
            String ref,
            Long amount_minor,
            String currency,
            boolean livemode,
            String status,
            String payment,
            Instant paid_at) {

        static StallPurchase of(StripePurchase p) {
            PurchaseStateController.PurchaseState state = PurchaseStateController.PurchaseState.of(p);
            return new StallPurchase(p.getVoucherId(), p.getPaymentIntentId(), p.getAmountMinor(),
                    p.getCurrency() == null ? null : p.getCurrency().toLowerCase(Locale.ROOT), p.isLivemode(),
                    p.getStatus() == null ? null : p.getStatus().name(), state.status(), p.getCreatedAt());
        }
    }
}
