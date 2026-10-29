package xyz.tcheeric.payment.adapter.stripe.webhook.gifting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import xyz.tcheeric.payment.adapter.core.model.entity.gifting.PurchaseDraft;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.PurchaseDraftRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;
import xyz.tcheeric.payment.adapter.stripe.webhook.purchase.StallReadinessClient;

/** imani-wallet#410: the Purchase Draft's checks, pinning, and one session per draft. */
class GiftingServiceTest {

    static final String STALL = "a".repeat(64);
    static final String BOB = "b".repeat(64);
    static final String SECRET = "1a".repeat(32);
    static final String R = pubkeyOf(SECRET);
    static final String WALLET = "https://wallet.imani.casa";
    static final String LINK = WALLET + "/gift-return#r=" + SECRET;

    static String pubkeyOf(String secret) {
        try {
            return java.util.HexFormat.of().formatHex(nostr.crypto.schnorr.Schnorr.genPubKey(java.util.HexFormat.of().parseHex(secret)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record Sent(String to, String key, Map<String, String> fields) {
    }

    final java.util.List<Sent> mails = new java.util.ArrayList<>();
    boolean mailerAccepts = true;
    static final String BUYER = "d".repeat(64);

    final Map<UUID, PurchaseDraft> store = new HashMap<>();
    PurchaseDraftRepository drafts;
    ConnectedStripeAccountRepository accounts;
    StallReadinessClient readiness;
    StripeCheckoutService checkout;
    AtomicReference<String> handleResolvesTo = new AtomicReference<>(BOB);
    boolean mx = true;
    MutableClock clock = new MutableClock(Instant.parse("2026-10-28T12:00:00Z"));
    ConnectedStripeAccount account;
    GiftingService service;

    @BeforeEach
    void setUp() {
        drafts = mock(PurchaseDraftRepository.class);
        when(drafts.save(any())).thenAnswer(i -> {
            PurchaseDraft d = i.getArgument(0);
            store.put(d.getId(), d);
            return d;
        });
        when(drafts.findById(any())).thenAnswer(i -> Optional.ofNullable(store.get((UUID) i.getArgument(0))));
        accounts = mock(ConnectedStripeAccountRepository.class);
        account = mock(ConnectedStripeAccount.class);
        when(account.isChargesEnabled()).thenReturn(true);
        when(account.getDefaultCurrency()).thenReturn("eur");
        when(account.getStripeAccountId()).thenReturn("acct_1");
        when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.of(account));
        readiness = mock(StallReadinessClient.class);
        when(readiness.check(STALL)).thenReturn(StallReadinessClient.Readiness.ready());
        checkout = mock(StripeCheckoutService.class);
        when(checkout.createPurchaseSession(anyString(), anyInt(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(StripeCheckoutSession.builder().sessionId("cs_1").sessionUrl("https://checkout.stripe.com/c/cs_1").build());
        service = new GiftingService(drafts, accounts, readiness, checkout,
                nip05 -> Optional.ofNullable(handleResolvesTo.get()), domain -> mx, clock, 1_000_000L,
                "https://gifting.imani.casa/return").withEnabledKinds(java.util.List.of("handle", "email"))
                .withBuyerRoles(pk -> buyerStanding.get())
                .withReturnLinkMail((to, key, fields) -> {
                    mails.add(new Sent(to, key, fields));
                    return mailerAccepts;
                }, WALLET + "/");
    }

    /** What the role read says about a signed-in buyer. A customer by default. */
    private final AtomicReference<BuyerRoleReader.Standing> buyerStanding = new AtomicReference<>(BuyerRoleReader.Standing.CUSTOMER);

    @Test
    void aStallSignedInIsRefusedGuestsAndCustomersAreNot() {
        var self = handle("bob@example.com");
        buyerStanding.set(BuyerRoleReader.Standing.MERCHANT);
        GiftingService.Answer stall = service.create(self, BUYER);
        assertEquals(HttpStatus.FORBIDDEN, stall.status());
        assertEquals(GiftingService.STALL_BUYER, error(stall));
        assertTrue(store.isEmpty());

        buyerStanding.set(BuyerRoleReader.Standing.UNKNOWN);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, service.create(self, BUYER).status());
        assertTrue(store.isEmpty());

        buyerStanding.set(BuyerRoleReader.Standing.CUSTOMER);
        assertEquals(HttpStatus.CREATED, service.create(self, BUYER).status());
    }

    @Test
    void withNoRoleReaderEverySignedInBuyerIsRefused() {
        GiftingService unwired = new GiftingService(drafts, accounts, readiness, checkout,
                nip05 -> Optional.of(BOB), domain -> true, clock, 1_000_000L, null);
        var self = handle("bob@example.com");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unwired.create(self, BUYER).status());
        assertTrue(store.isEmpty());
    }

    @Test
    void nothingIsSellableUntilConfigured() {
        GiftingService closed = new GiftingService(drafts, accounts, readiness, checkout,
                nip05 -> Optional.of(BOB), domain -> true, clock, 1_000_000L, null)
                .withBuyerRoles(pk -> BuyerRoleReader.Standing.CUSTOMER);
        assertEquals("Sending a gift this way isn't available yet.", error(closed.create(handle("bob@example.com"), null)));
        assertEquals("Sending a gift this way isn't available yet.", error(closed.create(email("bob@example.com"), null)));
        assertTrue(store.isEmpty());
    }

    GiftingService.DraftRequest handle(String h) {
        return new GiftingService.DraftRequest(STALL, "alice@imani.casa", 2000L, "EUR", "EUR:90:1",
                new GiftingService.Delivery("handle", h, null, R, null), "Happy birthday", "Ann", "me@example.com");
    }

    GiftingService.DraftRequest email(String e) {
        return new GiftingService.DraftRequest(STALL, "alice@imani.casa", 2000L, "EUR", "EUR:90:1",
                new GiftingService.Delivery("email", null, e, R, "v1.abc.def"), null, null, "me@example.com");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(GiftingService.Answer a) {
        return (Map<String, Object>) a.body();
    }

    static String error(GiftingService.Answer a) {
        return (String) body(a).get("error");
    }

    UUID created(GiftingService.Answer a) {
        assertEquals(HttpStatus.CREATED, a.status(), String.valueOf(a.body()));
        return UUID.fromString((String) body(a).get("draftId"));
    }

    // ─── creation and pinning ───────────────────────────────────────────────

    @Test
    void handleGiftIsResolvedAndPinned() {
        UUID id = created(service.create(handle("Bob@Example.com"), null));
        PurchaseDraft d = store.get(id);
        assertEquals("handle", d.getDeliveryKind());
        assertEquals(BOB, d.getRecipientPubkey());
        assertEquals(R, d.getRefundPubkey());
        assertEquals(Instant.parse("2026-10-28T12:30:00Z"), d.getExpiresAt());
        @SuppressWarnings("unchecked")
        Map<String, Object> delivery = (Map<String, Object>) body(service.get(id)).get("delivery");
        assertEquals(BOB, delivery.get("recipientPubkey"));
        assertEquals(30, delivery.get("claimWindowDays"));
    }

    @Test
    void aHandleThatMovesAfterReviewStillDeliversToTheReviewedKey() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        handleResolvesTo.set("e".repeat(64));
        assertEquals(HttpStatus.OK, service.pay(id, LINK).status());
        assertEquals(BOB, store.get(id).getRecipientPubkey());
        assertEquals(BOB, ((Map<?, ?>) body(service.get(id)).get("delivery")).get("recipientPubkey"));
    }

    @Test
    void unresolvableHandleIsRefused() {
        handleResolvesTo.set(null);
        GiftingService.Answer a = service.create(handle("nobody@example.com"), null);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, a.status());
        assertEquals("We couldn't find that handle.", error(a));
        assertTrue(store.isEmpty());
    }

    @Test
    void handleThatResolvesToTheStallIsRefused() {
        handleResolvesTo.set(STALL);
        assertEquals("You can't send a voucher to the stall that sells it.", error(service.create(handle("alice@imani.casa"), null)));
    }

    @Test
    void emailGiftPinsTheAddressAndKeepsTheSealHiddenFromReaders() {
        UUID id = created(service.create(email("bob@example.com"), null));
        assertEquals("v1.abc.def", store.get(id).getSealedSecret());
        assertNull(store.get(id).getRecipientPubkey());
        assertFalse(body(service.get(id)).toString().contains("v1.abc"), "GET never returns the sealed secret");
        assertFalse(body(service.get(id)).toString().contains("me@example.com"), "GET never returns the buyer's email");
    }

    @Test
    void emailDomainThatTakesNoMailIsRefused() {
        mx = false;
        assertEquals("That email address can't receive mail.", error(service.create(email("bob@exmaple.com"), null)));
    }

    @Test
    void emailGiftNeedsASeal() {
        var req = new GiftingService.DraftRequest(STALL, null, 2000L, "EUR", null,
                new GiftingService.Delivery("email", null, "bob@example.com", R, null), null, null, "me@example.com");
        assertEquals(HttpStatus.BAD_REQUEST, service.create(req, null).status());
    }

    @Test
    void selfIsNoLongerAGiftSignedInOrNot() {
        // "To myself" was removed (owner, 2026-10-29): even when enabled by config it is refused.
        GiftingService legacy = service.withEnabledKinds(java.util.List.of("self", "handle", "email"));
        var req = new GiftingService.DraftRequest(STALL, null, 2000L, "EUR", null,
                new GiftingService.Delivery("self", null, null, null, null), null, null, "me@example.com");
        assertEquals("Choose who the gift is for.", error(legacy.create(req, null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, legacy.create(req, BUYER).status());
        assertTrue(store.isEmpty());
    }

    @Test
    void guestsMustGiveAnEmail() {
        var req = new GiftingService.DraftRequest(STALL, null, 2000L, "EUR", null,
                new GiftingService.Delivery("handle", "bob@example.com", null, R, null), null, null, null);
        assertEquals(GiftingService.SENDER_EMAIL_REQUIRED, error(service.create(req, null)));
        assertEquals(GiftingService.SENDER_EMAIL_REQUIRED, error(service.create(req, BUYER)));
    }

    // Email gifts need the sender's email whether or not the buyer is signed in.
    @Test
    void emailGiftsNeedTheSendersEmailSignedInOrNot() {
        var req = new GiftingService.DraftRequest(STALL, null, 2000L, "EUR", null,
                new GiftingService.Delivery("email", null, "bob@example.com", R, "v1.abc.def"), null, null, " ");
        assertEquals(GiftingService.SENDER_EMAIL_REQUIRED, error(service.create(req, null)));
        assertEquals(GiftingService.SENDER_EMAIL_REQUIRED, error(service.create(req, BUYER)));
    }

    // A signed-in buyer's email is stored too, so the issuer can tell them about the claim.
    @Test
    void aSignedInBuyersEmailIsStored() {
        UUID id = created(service.create(handle("bob@example.com"), BUYER));
        assertEquals("me@example.com", store.get(id).getBuyerEmail());
    }

    // ─── the guest's return link (G15) ───────────────────────────────────────

    // A guest handle gift cannot be paid without its return link, and nothing is charged or mailed.
    @Test
    void guestPayWithoutAReturnLinkIsRefused() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        assertEquals(HttpStatus.BAD_REQUEST, service.pay(id, null).status());
        assertNoCheckoutAndNoMail();
    }

    // A link on another origin, or not shaped like one, is refused.
    @Test
    void guestPayWithABadReturnLinkIsRefused() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        for (String bad : java.util.List.of("https://evil.example/gift-return#r=" + SECRET, WALLET + "/gift-return#r=" + SECRET.toUpperCase(),
                WALLET + "/gift-return#r=abc", WALLET + "/gift-return?r=" + SECRET, LINK + "0")) {
            assertEquals(HttpStatus.BAD_REQUEST, service.pay(id, bad).status(), bad);
        }
        assertNoCheckoutAndNoMail();
    }

    // A well-formed link whose secret is not the draft's refund key is refused.
    @Test
    void guestPayWithSomeoneElsesReturnLinkIsRefused() {
        UUID id = created(service.create(email("bob@example.com"), null));
        GiftingService.Answer a = service.pay(id, WALLET + "/gift-return#r=" + "2".repeat(64));
        assertEquals(GiftingService.RETURN_LINK_INVALID, error(a));
        assertNoCheckoutAndNoMail();
    }

    // The first pay mails the link to the buyer with the draft's idempotency key, then charges.
    @Test
    void firstPayMailsTheReturnLinkBeforeCheckout() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        assertEquals(HttpStatus.OK, service.pay(id, LINK).status());
        assertEquals(1, mails.size());
        Sent sent = mails.get(0);
        assertEquals("me@example.com", sent.to());
        assertEquals(id + ":return-link", sent.key());
        // Without a profile name, never the handle or key: "the stall".
        assertEquals(Map.of("stall", "the stall", "claimBy", "27 November 2026", "returnUrl", LINK), sent.fields());
    }

    // The email names the stall as its page does: its signed profile name (G15 local run).
    @Test
    void theReturnLinkEmailNamesTheStallByItsProfileName() {
        service.withStallNames(pubkey -> java.util.Optional.of("Alice's Bakery"));
        UUID id = created(service.create(handle("bob@example.com"), null));
        assertEquals(HttpStatus.OK, service.pay(id, LINK).status());
        assertEquals("Alice's Bakery", mails.get(0).fields().get("stall"));
    }

    // A repeat pay answers the same session and sends no second email.
    @Test
    void repeatPaySendsNoSecondMail() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        service.pay(id, LINK);
        assertEquals(HttpStatus.OK, service.pay(id, null).status());
        assertEquals(1, mails.size());
    }

    // A refusing or unreachable mailer stops the purchase: 503, no checkout.
    @Test
    void aMailerThatDoesNotAcceptStopsThePurchase() {
        mailerAccepts = false;
        UUID id = created(service.create(handle("bob@example.com"), null));
        GiftingService.Answer a = service.pay(id, LINK);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, a.status());
        assertEquals("We couldn't email your return link. Try again.", error(a));
        verify(checkout, never()).createPurchaseSession(anyString(), anyInt(), anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    // The real HTTP client reports an unreachable mailer as not accepted.
    @Test
    void anUnreachableMailerIsNotAccepted() {
        var mailer = new ReturnLinkMailer.ImaniMailer("http://127.0.0.1:1", "t".repeat(32), Duration.ofSeconds(2));
        assertFalse(mailer.send("me@example.com", "k", Map.of("returnUrl", LINK)));
    }

    // Unconfigured mail refuses guest handle/email pay rather than charging without a link.
    @Test
    void withoutMailConfigGuestGiftsAreNotCharged() {
        service.withReturnLinkMail(null, null);
        UUID id = created(service.create(handle("bob@example.com"), null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, service.pay(id, LINK).status());
        assertNoCheckoutAndNoMail();
    }

    // Signed-in buyers pay without a return link: their wallet takes the gift back.
    @Test
    void signedInBuyersNeedNoReturnLink() {
        UUID id = created(service.create(handle("bob@example.com"), BUYER));
        assertEquals(HttpStatus.OK, service.pay(id, null).status());
        assertTrue(mails.isEmpty());
    }

    // The link is never echoed in an answer nor kept on the draft.
    @Test
    void theReturnLinkIsNeverEchoedOrStored() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        java.util.List<GiftingService.Answer> answers = java.util.List.of(service.pay(id, WALLET + "/gift-return#r=" + "2".repeat(64)),
                service.pay(id, LINK), service.pay(id, LINK), service.get(id));
        for (GiftingService.Answer a : answers) {
            assertFalse(String.valueOf(a.body()).contains(SECRET));
        }
        assertFalse(String.valueOf(store.get(id)).contains(SECRET));
        assertFalse(new xyz.tcheeric.payment.adapter.stripe.webhook.purchase.PurchaseCheckoutController.PurchaseRequest(
                null, null, null, id.toString(), LINK).toString().contains(SECRET));
    }

    private void assertNoCheckoutAndNoMail() {
        assertTrue(mails.isEmpty());
        verify(checkout, never()).createPurchaseSession(anyString(), anyInt(), anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void messagesArePlainTextWithoutLinks() {
        var withLink = new GiftingService.DraftRequest(STALL, null, 2000L, "EUR", null,
                new GiftingService.Delivery("handle", "bob@example.com", null, R, null), "go to https://x.example", null, "me@example.com");
        assertEquals("The message can't contain links.", error(service.create(withLink, null)));
        var html = new GiftingService.DraftRequest(STALL, null, 2000L, "EUR", null,
                new GiftingService.Delivery("handle", "bob@example.com", null, R, null), "<b>hi</b>", null, "me@example.com");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, service.create(html, null).status());
        var tooLong = new GiftingService.DraftRequest(STALL, null, 2000L, "EUR", null,
                new GiftingService.Delivery("handle", "bob@example.com", null, R, null), "x".repeat(281), null, "me@example.com");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, service.create(tooLong, null).status());
    }

    @Test
    void amountsOutsideTheBoundsAreRefused() {
        for (long amount : new long[] {0, -1, 1_000_001}) {
            var req = new GiftingService.DraftRequest(STALL, null, amount, "EUR", null,
                    new GiftingService.Delivery("handle", "bob@example.com", null, R, null), null, null, "me@example.com");
            assertEquals(HttpStatus.BAD_REQUEST, service.create(req, null).status(), "amount " + amount);
        }
    }

    @Test
    void stallRefusalsComeBeforeAnyDraft() {
        when(account.isChargesEnabled()).thenReturn(false);
        GiftingService.Answer a = service.create(handle("bob@example.com"), null);
        assertEquals(HttpStatus.CONFLICT, a.status());
        assertEquals("this stall cannot accept card payments yet", error(a));
        when(account.isChargesEnabled()).thenReturn(true);
        when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, service.create(handle("bob@example.com"), null).status());
        assertTrue(store.isEmpty());
    }

    @Test
    void aCurrencyThatIsNotTheStallsIsATermsChange() {
        when(account.getDefaultCurrency()).thenReturn("usd");
        assertEquals("STALL_TERMS_CHANGED", error(service.create(handle("bob@example.com"), null)));
    }

    @Test
    void readinessFailsClosed() {
        when(readiness.check(STALL)).thenReturn(StallReadinessClient.Readiness.unknown());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, service.create(handle("bob@example.com"), null).status());
    }

    @Test
    void perRecipientServiceLimitOnEmailGifts() {
        for (int i = 0; i < 10; i++) {
            assertEquals(HttpStatus.CREATED, service.create(email("bob@example.com"), null).status());
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, service.create(email("BOB@example.com"), null).status());
        assertEquals(HttpStatus.CREATED, service.create(email("carol@example.com"), null).status());
    }

    // ─── paying ─────────────────────────────────────────────────────────────

    @Test
    void oneCheckoutSessionPerDraftAndNoPersonalDataInStripe() {
        UUID id = created(service.create(email("bob@example.com"), null));
        GiftingService.Answer first = service.pay(id, LINK);
        GiftingService.Answer second = service.pay(id, LINK);
        assertEquals(body(first).get("checkoutUrl"), body(second).get("checkoutUrl"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(checkout, times(1)).createPurchaseSession(eq(id.toString()), eq(2000), eq("Voucher"), eq("eur"), eq("acct_1"),
                eq(null), metadata.capture(), eq("https://gifting.imani.casa/return?draft=" + id + "&session={CHECKOUT_SESSION_ID}"),
                eq("https://gifting.imani.casa/return?draft=" + id + "&cancelled=1"));
        assertEquals(Map.of("draft_id", id.toString()), metadata.getValue());
    }

    @Test
    void anExpiredDraftIsNeverCharged() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        clock.advance(Duration.ofMinutes(31));
        GiftingService.Answer a = service.pay(id, LINK);
        assertEquals(HttpStatus.GONE, a.status());
        assertEquals("DRAFT_EXPIRED", error(a));
        assertEquals(HttpStatus.GONE, service.get(id).status());
        verify(checkout, never()).createPurchaseSession(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anInvalidatedDraftIsNeverCharged() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        service.invalidate(id);
        assertEquals("DRAFT_INVALIDATED", error(service.pay(id, LINK)));
    }

    @Test
    void stallDisconnectingBetweenReviewAndPayIsRefusedWithItsWords() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        when(account.isChargesEnabled()).thenReturn(false);
        assertEquals("this stall cannot accept card payments yet", error(service.pay(id, LINK)));
        when(account.isChargesEnabled()).thenReturn(true);
        when(account.getDefaultCurrency()).thenReturn("gbp");
        assertEquals("STALL_TERMS_CHANGED", error(service.pay(id, LINK)));
    }

    @Test
    void aPaidDraftStaysReadableForTheReturnPageAndCannotBeInvalidated() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        service.pay(id, LINK);
        clock.advance(Duration.ofHours(2));
        service.invalidate(id);
        assertNull(store.get(id).getInvalidatedAt());
        assertEquals(HttpStatus.OK, service.get(id).status());
        assertEquals(HttpStatus.OK, service.pay(id, LINK).status(), "same session after the draft's own expiry");
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
