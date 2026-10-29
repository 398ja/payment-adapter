package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import xyz.tcheeric.payment.adapter.core.model.entity.gifting.PurchaseDraft;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.PurchaseDraftRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient;

/**
 * The Purchase Draft (imani-wallet#410 §3): create, read, invalidate, and pay.
 *
 * <p>Every check the gifting page made is made again here, because the page is public and the
 * client is not trusted: the stall can be charged, the currency is the stall's, the amount is in
 * bounds, a handle resolves (and its pubkey is PINNED), an email domain takes mail. Then
 * {@link #pay} creates exactly one Checkout Session per draft, whose only metadata is the draft
 * id: no personal data reaches Stripe, and the delivery target cannot change after review.
 *
 * <p>Nothing personal or secret is logged. Log lines name the draft id and the stall only.
 */
@Slf4j
public class GiftingService {

    public static final Duration DRAFT_TTL = Duration.ofMinutes(30);
    public static final int CLAIM_WINDOW_DAYS = 30;
    public static final int MESSAGE_MAX = 280;
    public static final int FROM_MAX = 80;

    static final String HANDLE_NOT_FOUND = "We couldn't find that handle.";
    static final String HANDLE_IS_STALL = "You can't send a voucher to the stall that sells it.";
    static final String EMAIL_UNDELIVERABLE = "That email address can't receive mail.";
    static final String STALL_BUYER = "Gifts are bought by customers. Sign in with a customer wallet, or buy as a guest.";
    static final String BUYER_UNCHECKED = "We couldn't check your account just now. Try again, or buy as a guest.";
    static final String BUYER_EMAIL_REQUIRED = "We need your email for the receipt and the return link.";
    static final String CHOOSE_RECIPIENT = "Choose who the gift is for.";
    static final String SENDER_EMAIL_REQUIRED = "We need your email to tell you when it's claimed.";
    static final String RETURN_LINK_REQUIRED = "A return link is required to pay for this gift.";
    static final String RETURN_LINK_INVALID = "That return link doesn't belong to this gift.";
    static final String RETURN_LINK_UNSENT = "We couldn't email your return link. Try again.";
    static final String RETURN_LINK_UNAVAILABLE = "Gifts to someone else can't be paid as a guest right now.";
    private static final java.time.format.DateTimeFormatter CLAIM_BY_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH).withZone(java.time.ZoneOffset.UTC);
    static final String MESSAGE_LINKS = "The message can't contain links.";
    static final String RATE_LIMITED = "Too many attempts. Please wait a minute and try again.";
    public static final String DRAFT_EXPIRED = "DRAFT_EXPIRED";
    public static final String DRAFT_INVALIDATED = "DRAFT_INVALIDATED";
    public static final String STALL_TERMS_CHANGED = "STALL_TERMS_CHANGED";
    static final String NOT_A_STALL_TAKING_CARDS = "this stall does not take card payments";
    static final String CHARGES_DISABLED = "this stall cannot accept card payments yet";
    static final String STALL_NOT_READY = "this stall cannot sell vouchers by card right now";
    static final String CARD_SALES_UNAVAILABLE = "card sales temporarily unavailable, try again shortly";

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern NIP05 = Pattern.compile(
            "^[a-z0-9._-]{1,64}@(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    private static final Pattern EMAIL = Pattern.compile(
            "^[^\\s@<>()\\[\\]\\\\,;:\"]{1,64}@((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63})$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URLISH = Pattern.compile(
            "(https?://|www\\.|\\b[a-z0-9-]+\\.(?:com|net|org|io|xyz|casa|co|me|app|link|ly)\\b)", Pattern.CASE_INSENSITIVE);

    private final PurchaseDraftRepository drafts;
    private final ConnectedStripeAccountRepository accounts;
    private final StallReadinessClient readiness;
    private final StripeCheckoutService checkout;
    private final GiftLookups.HandleResolver handles;
    private final GiftLookups.MailDomainChecker mail;
    private final Clock clock;
    private final long maxAmountMinor;
    /** {@code https://gifting.imani.casa/return}, or null for the deployment's purchase URLs. */
    private final String returnUrl;
    private final RateLimiter perRecipient;
    /**
     * Which deliveries may be SOLD. Fails closed: a handle or email gift is accepted only once
     * purchase-issuer can arm it (an issuer-authorised Cashlink lock, imani-wallet#366), because a
     * paid gift that cannot be delivered is a manual refund.
     */
    private java.util.Set<String> enabledKinds = java.util.Set.of();

    /** Guest return-link mailing; both unset refuses guest handle/email pay (fail closed). */
    private ReturnLinkMailer returnLinkMailer;
    private String walletOrigin;

    public GiftingService withReturnLinkMail(ReturnLinkMailer mailer, String walletOrigin) {
        this.returnLinkMailer = mailer;
        this.walletOrigin = walletOrigin == null || walletOrigin.isBlank() ? null : walletOrigin.trim().replaceAll("/+$", "");
        return this;
    }

    static final String KIND_NOT_YET = "Sending a gift this way isn't available yet.";

    public GiftingService withEnabledKinds(java.util.Collection<String> kinds) {
        this.enabledKinds = kinds.stream().map(k -> k.trim().toLowerCase()).filter(k -> !k.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return this;
    }

    /** Who may buy while signed in: customers only (never a stall). Unset refuses every signed-in buyer. */
    private BuyerRoleReader.Reader buyerRoles = pubkey -> BuyerRoleReader.Standing.UNKNOWN;

    public GiftingService withBuyerRoles(BuyerRoleReader.Reader reader) {
        this.buyerRoles = reader;
        return this;
    }

    public GiftingService(PurchaseDraftRepository drafts, ConnectedStripeAccountRepository accounts,
            StallReadinessClient readiness, StripeCheckoutService checkout, GiftLookups.HandleResolver handles,
            GiftLookups.MailDomainChecker mail, Clock clock, long maxAmountMinor, String returnUrl) {
        this.drafts = drafts;
        this.accounts = accounts;
        this.readiness = readiness;
        this.checkout = checkout;
        this.handles = handles;
        this.mail = mail;
        this.clock = clock;
        this.maxAmountMinor = maxAmountMinor;
        this.returnUrl = returnUrl == null || returnUrl.isBlank() ? null : returnUrl;
        this.perRecipient = new RateLimiter(10, 10.0 / 3600, clock);
    }

    /** An answer for the controller: a status and a body. */
    public record Answer(HttpStatus status, Object body) {
        static Answer error(HttpStatus status, String message) {
            return new Answer(status, Map.of("error", message));
        }
    }

    public record Delivery(String kind, String handle, String email, String refundPubkey, String sealed) {
    }

    public record DraftRequest(String stallPubkey, String stallHandle, Long amountMinor, String currency,
            String termsVersion, Delivery delivery, String message, String from, String buyerEmail) {
    }

    // ─── create ─────────────────────────────────────────────────────────────

    /** @param buyerPubkey the NIP-98 signer, or null for a guest */
    public Answer create(DraftRequest req, String buyerPubkey) {
        if (req == null || req.stallPubkey() == null || req.amountMinor() == null || req.delivery() == null
                || req.delivery().kind() == null || req.currency() == null) {
            return Answer.error(HttpStatus.BAD_REQUEST, "a stall, an amount, a currency and a delivery are required");
        }
        // Customers and guests only (owner, 2026-10-28): a signed-in stall does not buy gifts here.
        // A role that cannot be checked is not assumed to be the permitted one.
        if (buyerPubkey != null) {
            BuyerRoleReader.Standing standing = buyerRoles.standingOf(buyerPubkey);
            if (standing == BuyerRoleReader.Standing.MERCHANT) {
                return Answer.error(HttpStatus.FORBIDDEN, STALL_BUYER);
            }
            if (standing != BuyerRoleReader.Standing.CUSTOMER) {
                return Answer.error(HttpStatus.SERVICE_UNAVAILABLE, BUYER_UNCHECKED);
            }
        }
        String stall = req.stallPubkey().trim().toLowerCase();
        if (!HEX64.matcher(stall).matches()) {
            return Answer.error(HttpStatus.BAD_REQUEST, "that is not a stall address");
        }
        if (req.amountMinor() <= 0) {
            return Answer.error(HttpStatus.BAD_REQUEST, "the amount must be more than zero");
        }
        if (req.amountMinor() > maxAmountMinor) {
            return Answer.error(HttpStatus.BAD_REQUEST, "that amount is too large for one voucher");
        }
        String message = req.message() == null ? null : req.message().trim();
        if (message != null && !message.isEmpty()) {
            if (message.length() > MESSAGE_MAX) {
                return Answer.error(HttpStatus.UNPROCESSABLE_ENTITY, "Keep the message to 280 characters.");
            }
            if (message.contains("<") || message.contains(">")) {
                return Answer.error(HttpStatus.UNPROCESSABLE_ENTITY, "The message must be plain text.");
            }
            if (URLISH.matcher(message).find()) {
                return Answer.error(HttpStatus.UNPROCESSABLE_ENTITY, MESSAGE_LINKS);
            }
        } else {
            message = null;
        }
        String from = req.from() == null || req.from().isBlank() ? null : req.from().trim();
        if (from != null && (from.length() > FROM_MAX || from.contains("<") || URLISH.matcher(from).find())) {
            return Answer.error(HttpStatus.UNPROCESSABLE_ENTITY, "Keep the name to 80 characters, plain text.");
        }

        // A gift is always for someone else (owner, 2026-10-29): "to myself" is the wallet's Buy page.
        if (PurchaseDraft.SELF.equalsIgnoreCase(req.delivery().kind().trim())) {
            return Answer.error(HttpStatus.UNPROCESSABLE_ENTITY, CHOOSE_RECIPIENT);
        }
        if (!enabledKinds.contains(req.delivery().kind().trim().toLowerCase())) {
            return Answer.error(HttpStatus.UNPROCESSABLE_ENTITY, KIND_NOT_YET);
        }
        Optional<String> givenEmail = Optional.ofNullable(req.buyerEmail()).map(String::trim)
                .filter(e -> e.length() <= 320 && EMAIL.matcher(e).matches());
        if (givenEmail.isEmpty()) {
            return Answer.error(HttpStatus.UNPROCESSABLE_ENTITY, SENDER_EMAIL_REQUIRED);
        }
        String buyerEmail = givenEmail.orElse(null);

        // Stall checks: the same refusals, in the same order, as a wallet card purchase.
        Optional<Answer> refused = stallRefusal(stall, req.currency());
        if (refused.isPresent()) {
            return refused.get();
        }

        PurchaseDraft d = new PurchaseDraft();
        d.setId(UUID.randomUUID());
        d.setStallPubkey(stall);
        d.setStallHandle(req.stallHandle() == null ? null : req.stallHandle().trim().toLowerCase());
        d.setAmountMinor(req.amountMinor());
        d.setCurrency(req.currency().trim().toUpperCase());
        d.setTermsVersion(req.termsVersion());
        d.setMessage(message);
        d.setSenderName(from);
        d.setBuyerPubkey(buyerPubkey);
        d.setBuyerEmail(buyerEmail);

        Delivery delivery = req.delivery();
        String kind = delivery.kind().trim().toLowerCase();
        String refund = delivery.refundPubkey() == null ? null : delivery.refundPubkey().trim().toLowerCase();
        switch (kind) {
            case PurchaseDraft.HANDLE -> {
                if (refund == null || !HEX64.matcher(refund).matches()) {
                    return Answer.error(HttpStatus.BAD_REQUEST, "a refund key is required");
                }
                String handle = delivery.handle() == null ? "" : delivery.handle().trim().toLowerCase().replaceFirst("^@", "");
                if (!NIP05.matcher(handle).matches()) {
                    return Answer.error(HttpStatus.UNPROCESSABLE_ENTITY, HANDLE_NOT_FOUND);
                }
                // Re-resolved HERE and pinned: the handle can move later, the gift will not (§Edge cases).
                Optional<String> pubkey = handles.resolve(handle).map(String::toLowerCase).filter(p -> HEX64.matcher(p).matches());
                if (pubkey.isEmpty()) {
                    return Answer.error(HttpStatus.UNPROCESSABLE_ENTITY, HANDLE_NOT_FOUND);
                }
                if (pubkey.get().equals(stall)) {
                    return Answer.error(HttpStatus.UNPROCESSABLE_ENTITY, HANDLE_IS_STALL);
                }
                d.setDeliveryKind(PurchaseDraft.HANDLE);
                d.setRecipientPubkey(pubkey.get());
                d.setRefundPubkey(refund);
            }
            case PurchaseDraft.EMAIL -> {
                if (refund == null || !HEX64.matcher(refund).matches()) {
                    return Answer.error(HttpStatus.BAD_REQUEST, "a refund key is required");
                }
                String email = delivery.email() == null ? "" : delivery.email().trim();
                var m = EMAIL.matcher(email);
                if (email.length() > 254 || !m.matches()) {
                    return Answer.error(HttpStatus.UNPROCESSABLE_ENTITY, "Enter an email address.");
                }
                if (!perRecipient.tryAcquire("rcpt:" + email.toLowerCase())) {
                    return Answer.error(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED);
                }
                if (!mail.acceptsMail(m.group(1).toLowerCase())) {
                    return Answer.error(HttpStatus.UNPROCESSABLE_ENTITY, EMAIL_UNDELIVERABLE);
                }
                String sealed = delivery.sealed();
                if (sealed == null || sealed.isBlank() || sealed.length() > 2048 || !sealed.startsWith("v1.")) {
                    return Answer.error(HttpStatus.BAD_REQUEST, "the gift's sealed link is missing");
                }
                d.setDeliveryKind(PurchaseDraft.EMAIL);
                d.setRecipientEmail(email);
                d.setRefundPubkey(refund);
                d.setSealedSecret(sealed);
            }
            default -> {
                return Answer.error(HttpStatus.BAD_REQUEST, "unknown delivery");
            }
        }
        Instant now = clock.instant();
        d.setCreatedAt(now);
        d.setExpiresAt(now.plus(DRAFT_TTL));
        drafts.save(d);
        log.info("Gift draft {} created for stall {} ({} {}, {})", d.getId(), stall, d.getAmountMinor(), d.getCurrency(), d.getDeliveryKind());
        return new Answer(HttpStatus.CREATED, view(d));
    }

    // ─── read / invalidate ──────────────────────────────────────────────────

    public Answer get(UUID id) {
        Optional<PurchaseDraft> found = drafts.findById(id);
        if (found.isEmpty()) {
            return Answer.error(HttpStatus.NOT_FOUND, "no such gift");
        }
        PurchaseDraft d = found.get();
        // A paid draft stays readable for the return page; an unpaid one only until it expires.
        if (d.getConsumedAt() == null && d.getCheckoutSessionId() == null && d.isExpired(clock.instant())) {
            return Answer.error(HttpStatus.GONE, DRAFT_EXPIRED);
        }
        return new Answer(HttpStatus.OK, view(d));
    }

    public Answer invalidate(UUID id) {
        drafts.findById(id).ifPresent(d -> {
            if (d.getInvalidatedAt() == null && d.getCheckoutSessionId() == null) {
                d.setInvalidatedAt(clock.instant());
                drafts.save(d);
            }
        });
        return new Answer(HttpStatus.NO_CONTENT, null);
    }

    // ─── pay ────────────────────────────────────────────────────────────────

    /**
     * One Checkout Session per draft (G-R6). A second Pay, a refresh or a second tab answers the
     * same session URL; two racing calls are settled by the draft's version and the Stripe
     * idempotency key, which is derived from the draft id.
     */
    public Answer pay(UUID id) {
        return pay(id, null);
    }

    /**
     * @param returnLink the guest's {@code <wallet>/gift-return#r=<hex>}: required for a guest
     *     handle/email gift, mailed once, then forgotten. Never stored, logged or echoed.
     */
    public Answer pay(UUID id, String returnLink) {
        Optional<PurchaseDraft> found = drafts.findById(id);
        if (found.isEmpty()) {
            return Answer.error(HttpStatus.NOT_FOUND, "no such gift");
        }
        PurchaseDraft d = found.get();
        if (d.getCheckoutUrl() != null) {
            return new Answer(HttpStatus.OK, Map.of("checkoutUrl", d.getCheckoutUrl(), "reference", d.getId().toString()));
        }
        if (d.getInvalidatedAt() != null) {
            return Answer.error(HttpStatus.CONFLICT, DRAFT_INVALIDATED);
        }
        if (d.isExpired(clock.instant())) {
            return Answer.error(HttpStatus.GONE, DRAFT_EXPIRED);
        }
        Optional<Answer> refused = stallRefusal(d.getStallPubkey(), d.getCurrency());
        if (refused.isPresent()) {
            return refused.get();
        }
        Optional<Answer> unmailed = mailReturnLinkIfGuest(d, returnLink);
        if (unmailed.isPresent()) {
            return unmailed.get();
        }
        ConnectedStripeAccount account = accounts.findByMerchantPubkey(d.getStallPubkey()).orElseThrow();

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("draft_id", d.getId().toString());
        // Back-compat for a self purchase: the issuer already knows buyer_pubkey. A pubkey is not
        // personal data in the sense of G9 (it is the buyer's own wallet), and only for "self".
        if (PurchaseDraft.SELF.equals(d.getDeliveryKind())) {
            metadata.put("buyer_pubkey", d.getRecipientPubkey());
        }
        String success = returnUrl == null ? null : returnUrl + "?draft=" + d.getId() + "&session={CHECKOUT_SESSION_ID}";
        String cancel = returnUrl == null ? null : returnUrl + "?draft=" + d.getId() + "&cancelled=1";
        StripeCheckoutSession session;
        try {
            session = checkout.createPurchaseSession(d.getId().toString(), Math.toIntExact(d.getAmountMinor()), "Voucher",
                    account.getDefaultCurrency(), account.getStripeAccountId(), null, metadata, success, cancel);
        } catch (xyz.tcheeric.payment.adapter.stripe.gateway.exception.StripeCheckoutCreationException e) {
            log.warn("Could not start checkout for gift draft {}: {}", d.getId(), e.getMessage());
            return e.isStripeUnavailable()
                    ? Answer.error(HttpStatus.SERVICE_UNAVAILABLE, "card payments are briefly unavailable, try again")
                    : Answer.error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        d.setCheckoutSessionId(session.getSessionId());
        d.setCheckoutUrl(session.getSessionUrl());
        try {
            drafts.save(d);
        } catch (ObjectOptimisticLockingFailureException raced) {
            // The other click won and stored its session (Stripe's idempotency key made it the same one).
            PurchaseDraft winner = drafts.findById(id).orElseThrow();
            if (winner.getCheckoutUrl() != null) {
                return new Answer(HttpStatus.OK, Map.of("checkoutUrl", winner.getCheckoutUrl(), "reference", id.toString()));
            }
            throw raced;
        }
        log.info("Gift draft {} checkout started for stall {}", d.getId(), d.getStallPubkey());
        return new Answer(HttpStatus.OK, Map.of("checkoutUrl", session.getSessionUrl(), "reference", d.getId().toString()));
    }

    private Optional<Answer> mailReturnLinkIfGuest(PurchaseDraft d, String returnLink) {
        if (!needsReturnLink(d)) {
            return Optional.empty();
        }
        if (returnLinkMailer == null || walletOrigin == null) {
            return Optional.of(Answer.error(HttpStatus.SERVICE_UNAVAILABLE, RETURN_LINK_UNAVAILABLE));
        }
        if (returnLink == null || returnLink.isBlank()) {
            return Optional.of(Answer.error(HttpStatus.BAD_REQUEST, RETURN_LINK_REQUIRED));
        }
        if (!returnLinkMatches(returnLink.trim(), d.getRefundPubkey())) {
            return Optional.of(Answer.error(HttpStatus.BAD_REQUEST, RETURN_LINK_INVALID));
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("stall", d.getStallHandle() == null || d.getStallHandle().isBlank() ? "the stall" : d.getStallHandle());
        fields.put("claimBy", CLAIM_BY_FORMAT.format(claimBy(d)));
        fields.put("returnUrl", returnLink.trim());
        if (!returnLinkMailer.send(d.getBuyerEmail(), d.getId() + ":return-link", fields)) {
            log.warn("Gift draft {} not charged: the return-link email was not accepted", d.getId());
            return Optional.of(Answer.error(HttpStatus.SERVICE_UNAVAILABLE, RETURN_LINK_UNSENT));
        }
        return Optional.empty();
    }

    private static boolean needsReturnLink(PurchaseDraft d) {
        return d.getBuyerPubkey() == null && !PurchaseDraft.SELF.equals(d.getDeliveryKind());
    }

    /** The end of the claim window as the draft view states it: creation + {@link #CLAIM_WINDOW_DAYS}. */
    static Instant claimBy(PurchaseDraft d) {
        return d.getCreatedAt().plus(Duration.ofDays(CLAIM_WINDOW_DAYS));
    }

    private boolean returnLinkMatches(String link, String refundPubkey) {
        String prefix = walletOrigin + "/gift-return#r=";
        if (!link.startsWith(prefix)) {
            return false;
        }
        String secret = link.substring(prefix.length());
        if (!HEX64.matcher(secret).matches() || refundPubkey == null) {
            return false;
        }
        try {
            byte[] pub = nostr.crypto.schnorr.Schnorr.genPubKey(java.util.HexFormat.of().parseHex(secret));
            return java.util.HexFormat.of().formatHex(pub).equals(refundPubkey);
        } catch (Exception notAKey) {
            return false;
        }
    }

    /** The checks every wallet card purchase makes, plus the draft's own currency against the stall's. */
    private Optional<Answer> stallRefusal(String stall, String currency) {
        Optional<ConnectedStripeAccount> found = accounts.findByMerchantPubkey(stall);
        if (found.isEmpty()) {
            return Optional.of(Answer.error(HttpStatus.NOT_FOUND, NOT_A_STALL_TAKING_CARDS));
        }
        ConnectedStripeAccount account = found.get();
        if (!account.isChargesEnabled()) {
            return Optional.of(Answer.error(HttpStatus.CONFLICT, CHARGES_DISABLED));
        }
        String stallCurrency = account.getDefaultCurrency();
        if (stallCurrency == null || stallCurrency.isBlank()) {
            return Optional.of(Answer.error(HttpStatus.CONFLICT, "this stall has no currency configured"));
        }
        if (!stallCurrency.trim().equalsIgnoreCase(currency.trim())) {
            return Optional.of(Answer.error(HttpStatus.CONFLICT, STALL_TERMS_CHANGED));
        }
        StallReadinessClient.Readiness ready = readiness.check(stall);
        if (ready.state() == StallReadinessClient.State.NOT_READY) {
            return Optional.of(Answer.error(HttpStatus.CONFLICT, STALL_NOT_READY));
        }
        if (ready.state() != StallReadinessClient.State.READY) {
            return Optional.of(Answer.error(HttpStatus.SERVICE_UNAVAILABLE, CARD_SALES_UNAVAILABLE));
        }
        return Optional.empty();
    }

    /** What the gifting page sees: never the sealed secret, never the buyer's email. */
    static Map<String, Object> view(PurchaseDraft d) {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("kind", d.getDeliveryKind());
        if (d.getRecipientPubkey() != null) {
            delivery.put("recipientPubkey", d.getRecipientPubkey());
        }
        if (d.getRecipientEmail() != null) {
            delivery.put("email", d.getRecipientEmail());
        }
        if (d.getRefundPubkey() != null) {
            delivery.put("refundPubkey", d.getRefundPubkey());
        }
        delivery.put("claimWindowDays", CLAIM_WINDOW_DAYS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("draftId", d.getId().toString());
        out.put("expiresAt", d.getExpiresAt().toString());
        out.put("stallPubkey", d.getStallPubkey());
        out.put("amountMinor", d.getAmountMinor());
        out.put("currency", d.getCurrency());
        out.put("delivery", delivery);
        if (d.getMessage() != null) {
            out.put("message", d.getMessage());
        }
        if (d.getSenderName() != null) {
            out.put("from", d.getSenderName());
        }
        out.put("paid", d.getConsumedAt() != null);
        return out;
    }
}
