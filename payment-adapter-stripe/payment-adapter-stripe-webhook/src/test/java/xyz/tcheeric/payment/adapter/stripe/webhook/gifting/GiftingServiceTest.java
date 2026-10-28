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
    static final String R = "c".repeat(64);
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
                "https://gifting.imani.casa/return").withEnabledKinds(java.util.List.of("self", "handle", "email"));
    }

    @Test
    void handleAndEmailGiftsAreRefusedUntilEnabled() {
        GiftingService closed = new GiftingService(drafts, accounts, readiness, checkout,
                nip05 -> Optional.of(BOB), domain -> true, clock, 1_000_000L, null);
        assertEquals("Sending a gift this way isn't available yet.", error(closed.create(handle("bob@example.com"), null)));
        assertEquals("Sending a gift this way isn't available yet.", error(closed.create(email("bob@example.com"), null)));
        var self = new GiftingService.DraftRequest(STALL, null, 2000L, "EUR", null,
                new GiftingService.Delivery("self", null, null, null, null), null, null, null);
        assertEquals(HttpStatus.CREATED, closed.create(self, BUYER).status());
        assertTrue(store.size() == 1);
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
        assertEquals(HttpStatus.OK, service.pay(id).status());
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
    void selfNeedsASignedInBuyerAndDeliversToTheSigner() {
        var req = new GiftingService.DraftRequest(STALL, null, 2000L, "EUR", null,
                new GiftingService.Delivery("self", null, null, null, null), null, null, null);
        assertEquals(HttpStatus.UNAUTHORIZED, service.create(req, null).status());
        UUID id = created(service.create(req, BUYER));
        assertEquals(BUYER, store.get(id).getRecipientPubkey());
        assertNull(store.get(id).getBuyerEmail());
    }

    @Test
    void guestsMustGiveAnEmail() {
        var req = new GiftingService.DraftRequest(STALL, null, 2000L, "EUR", null,
                new GiftingService.Delivery("handle", "bob@example.com", null, R, null), null, null, null);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, service.create(req, null).status());
        assertEquals(HttpStatus.CREATED, service.create(req, BUYER).status());
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
        GiftingService.Answer first = service.pay(id);
        GiftingService.Answer second = service.pay(id);
        assertEquals(body(first).get("checkoutUrl"), body(second).get("checkoutUrl"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(checkout, times(1)).createPurchaseSession(eq(id.toString()), eq(2000), eq("Voucher"), eq("eur"), eq("acct_1"),
                eq(null), metadata.capture(), eq("https://gifting.imani.casa/return?draft=" + id + "&session={CHECKOUT_SESSION_ID}"),
                eq("https://gifting.imani.casa/return?draft=" + id + "&cancelled=1"));
        assertEquals(Map.of("draft_id", id.toString()), metadata.getValue());
    }

    @Test
    void selfPurchaseAlsoCarriesTheBuyersOwnKey() {
        var req = new GiftingService.DraftRequest(STALL, null, 2000L, "EUR", null,
                new GiftingService.Delivery("self", null, null, null, null), null, null, null);
        UUID id = created(service.create(req, BUYER));
        service.pay(id);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(checkout).createPurchaseSession(anyString(), anyInt(), anyString(), anyString(), anyString(), any(), metadata.capture(), any(), any());
        assertEquals(Map.of("draft_id", id.toString(), "buyer_pubkey", BUYER), metadata.getValue());
    }

    @Test
    void anExpiredDraftIsNeverCharged() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        clock.advance(Duration.ofMinutes(31));
        GiftingService.Answer a = service.pay(id);
        assertEquals(HttpStatus.GONE, a.status());
        assertEquals("DRAFT_EXPIRED", error(a));
        assertEquals(HttpStatus.GONE, service.get(id).status());
        verify(checkout, never()).createPurchaseSession(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anInvalidatedDraftIsNeverCharged() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        service.invalidate(id);
        assertEquals("DRAFT_INVALIDATED", error(service.pay(id)));
    }

    @Test
    void stallDisconnectingBetweenReviewAndPayIsRefusedWithItsWords() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        when(account.isChargesEnabled()).thenReturn(false);
        assertEquals("this stall cannot accept card payments yet", error(service.pay(id)));
        when(account.isChargesEnabled()).thenReturn(true);
        when(account.getDefaultCurrency()).thenReturn("gbp");
        assertEquals("STALL_TERMS_CHANGED", error(service.pay(id)));
    }

    @Test
    void aPaidDraftStaysReadableForTheReturnPageAndCannotBeInvalidated() {
        UUID id = created(service.create(handle("bob@example.com"), null));
        service.pay(id);
        clock.advance(Duration.ofHours(2));
        service.invalidate(id);
        assertNull(store.get(id).getInvalidatedAt());
        assertEquals(HttpStatus.OK, service.get(id).status());
        assertEquals(HttpStatus.OK, service.pay(id).status(), "same session after the draft's own expiry");
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
