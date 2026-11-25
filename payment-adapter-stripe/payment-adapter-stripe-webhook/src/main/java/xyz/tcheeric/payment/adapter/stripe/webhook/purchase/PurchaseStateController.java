package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePurchase;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePurchaseRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.gifting.Nip98Verifier;

/**
 * Refund and dispute state of card purchases, read-only (imani-wallet#277, payment-adapter#265).
 *
 * <p>Deliberately NOT under {@code /api/v1/purchases}: that prefix is the issuer's, behind
 * {@code PURCHASE_API_TOKEN}, which can claim, discharge and fail every purchase. Nothing here
 * accepts that token, and nothing here writes. Two readers, two credentials:
 *
 * <ul>
 *   <li><b>A stall</b>, for its own sales: {@code GET /stall/{stallPubkey}} with a NIP-98
 *       {@code Authorization: Nostr ...} event signed BY that stall key, for this exact URL and
 *       {@code GET}, within 60 s. Another key, a stale event or a different URL is 401/403. The
 *       signed {@code u} must be one of {@code purchase.state.public-origins} plus the path, so a
 *       proof made for another service is not replayable here.</li>
 *   <li><b>The gateway's redeem guard</b>, by the processor reference a delegated warrant carries
 *       (a Stripe {@code pi_} or a PayPal capture id, {@link ProcessorReference}):
 *       {@code GET /ref/{pi}} with {@code Authorization: Bearer <purchase.state.read-token>}. A
 *       separate, read-only credential. Unset, or equal to the issuer's or operator's token, refuses
 *       every call (503): a gateway must never end up holding the issuer's power.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping(PurchaseStateController.PREFIX)
public class PurchaseStateController {

    public static final String PREFIX = "/api/v1/purchase-state";
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    private final StripePurchaseRepository purchases;
    private final Nip98Verifier nip98;
    private final List<String> publicOrigins;
    private final byte[] readToken;
    private final xyz.tcheeric.payment.adapter.stripe.webhook.gifting.RateLimiter holderLimit =
            new xyz.tcheeric.payment.adapter.stripe.webhook.gifting.RateLimiter(30, 0.5, java.time.Clock.systemUTC());

    @org.springframework.beans.factory.annotation.Autowired
    public PurchaseStateController(StripePurchaseRepository purchases,
            @org.springframework.beans.factory.annotation.Value("${purchase.state.public-origins:}") List<String> publicOrigins,
            @org.springframework.beans.factory.annotation.Value("${purchase.state.read-token:}") String readToken,
            @org.springframework.beans.factory.annotation.Value("${purchase.api.token:}") String issuerToken,
            @org.springframework.beans.factory.annotation.Value("${purchase.operator.token:}") String operatorToken) {
        this(purchases, new Nip98Verifier(), publicOrigins, readToken, issuerToken, operatorToken);
    }

    public PurchaseStateController(StripePurchaseRepository purchases, Nip98Verifier nip98,
            List<String> publicOrigins, String readToken, String issuerToken, String operatorToken) {
        this.purchases = purchases;
        this.nip98 = nip98;
        this.publicOrigins = publicOrigins == null ? List.of()
                : publicOrigins.stream().map(String::trim).filter(o -> !o.isEmpty())
                        .map(o -> o.replaceAll("/+$", "")).toList();
        this.readToken = usableReadToken(readToken, issuerToken, operatorToken);
    }

    private static byte[] usableReadToken(String read, String issuer, String operator) {
        if (read == null || read.isBlank()) {
            log.warn("No purchase state read token configured; {}/ref/* will refuse every request", PREFIX);
            return null;
        }
        if (read.equals(issuer) || read.equals(operator)) {
            log.warn("The purchase state read token equals the issuer's or operator's; {}/ref/* will refuse every request",
                    PREFIX);
            return null;
        }
        return read.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * One stall's recent card purchases with their refund state, newest first (at most 200).
     *
     * @return 200 list, 400 malformed pubkey, 401 no or bad NIP-98, 403 signed by another key,
     *         503 no public origin configured
     */
    @GetMapping("/stall/{stallPubkey}")
    public ResponseEntity<?> byStall(@PathVariable String stallPubkey,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String stall = stallPubkey == null ? "" : stallPubkey.toLowerCase(Locale.ROOT);
        if (!HEX64.matcher(stall).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "stall pubkey must be 64 hex characters"));
        }
        if (publicOrigins.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "purchase state is not configured"));
        }
        Optional<String> signer = signerFor(authorization, PREFIX + "/stall/" + stallPubkey);
        if (signer.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "NIP-98 authorization required"));
        }
        if (!signer.get().equalsIgnoreCase(stall)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "signed by another key"));
        }
        return ResponseEntity.ok(purchases.findTop200ByStallPubkeyOrderByCreatedAtDesc(stall).stream()
                .map(PurchaseState::of).toList());
    }

    /**
     * One purchase by its Stripe payment intent, for the gateway's redeem guard.
     *
     * @return 200 state, 400 not a processor ref, 401 wrong or no token, 404 unknown ref, 503 unconfigured
     */
    @GetMapping("/ref/{ref}")
    public ResponseEntity<?> byRef(@PathVariable String ref,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (readToken == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "purchase state read token is not configured"));
        }
        if (authorization == null || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(readToken,
                        authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!ProcessorReference.isWellFormed(ref)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "ref must be a Stripe payment intent (pi_...) or a PayPal capture id"));
        }
        return purchases.findByPaymentIntentId(ref)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(PurchaseState.of(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * What a voucher's HOLDER may know about its own payment (imani-wallet#277): status and amounts,
     * nothing else (no voucher id, no stall, no account). The {@code pi_} is the capability: it is
     * carried only inside the signed voucher and on the stall's Stripe account, and is not guessable.
     * Rate limited per client address. An unknown ref and a malformed one both answer 404, so the
     * route cannot be used to tell the two apart.
     */
    @GetMapping("/holder/{ref}")
    public ResponseEntity<?> forHolder(@PathVariable String ref, jakarta.servlet.http.HttpServletRequest http) {
        // Per client, not per edge: behind nginx the remote address is the proxy's, so the same
        // rule as gifting (first X-Forwarded-For hop) keeps one abuser from locking everyone out.
        String ip = http == null ? "unknown"
                : java.util.Objects.requireNonNullElse(
                        xyz.tcheeric.payment.adapter.stripe.webhook.gifting.GiftingController.clientIp(http), "unknown");
        if (!holderLimit.tryAcquire(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        if (!ProcessorReference.isWellFormed(ref)) {
            return ResponseEntity.notFound().build();
        }
        return purchases.findByPaymentIntentId(ref)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(HolderState.of(PurchaseState.of(p))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The holder's view: no voucher id, ref, dispute detail or creation date. */
    public record HolderState(Long paid_minor, String currency, long refunded_amount_minor, boolean disputed,
                              long backed_minor, String status, Instant reversed_at) {
        static HolderState of(PurchaseState s) {
            return new HolderState(s.paid_minor(), s.currency(), s.refunded_amount_minor(), s.disputed(),
                    s.backed_minor(), s.status(), s.reversed_at());
        }
    }

    private Optional<String> signerFor(String authorization, String path) {
        for (String origin : publicOrigins) {
            Optional<String> signer = nip98.verify(authorization, origin + path, "GET", null);
            if (signer.isPresent()) {
                return signer;
            }
        }
        return Optional.empty();
    }

    /**
     * A purchase's backing, in the terms #277 names. {@code backed_minor} is paid - refunded, and 0
     * while a dispute blocks it. No Stripe account, charge or session id, and no buyer data.
     *
     * @param status {@code REFUNDED}, {@code PARTLY_REFUNDED}, {@code DISPUTED} or {@code PAID}
     */
    public record PurchaseState(
            String voucher_id,
            String ref,
            Long paid_minor,
            String currency,
            long refunded_amount_minor,
            boolean disputed,
            String dispute_status,
            Instant reversed_at,
            long backed_minor,
            String status,
            Instant created_at) {

        static PurchaseState of(StripePurchase p) {
            long paid = p.getAmountMinor() == null ? 0 : p.getAmountMinor();
            long refunded = p.getRefundedAmountMinor() == null ? 0 : Math.min(paid, p.getRefundedAmountMinor());
            long backed = p.isDisputed() ? 0 : Math.max(0, paid - refunded);
            String status;
            if (p.isDisputed()) {
                status = "DISPUTED";
            } else if (refunded > 0 && refunded >= paid) {
                status = "REFUNDED";
            } else if (refunded > 0) {
                status = "PARTLY_REFUNDED";
            } else {
                status = "PAID";
            }
            return new PurchaseState(p.getVoucherId(), p.getPaymentIntentId(), p.getAmountMinor(),
                    p.getCurrency() == null ? null : p.getCurrency().toLowerCase(Locale.ROOT), refunded,
                    p.isDisputed(), p.getDisputeStatus() == null ? null : p.getDisputeStatus().name(),
                    p.getReversedAt(), backed, status, p.getCreatedAt());
        }
    }
}
