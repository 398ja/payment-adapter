package xyz.tcheeric.payment.adapter.stripe.webhook.purchase;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ConnectedStripeAccount;
import xyz.tcheeric.payment.adapter.core.model.repository.ConnectedStripeAccountRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.exception.StripeCheckoutCreationException;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A guest purchase is refused BEFORE a Checkout Session exists (imani-wallet#267
 * item 2): claim-link delivery is not wired, so a guest who paid would never
 * receive the coupon. No session means nothing can be charged.
 */
class PurchaseCheckoutControllerTest {

    private static final String STALL = "b".repeat(64);
    private static final String BUYER = "a".repeat(64);

    private ConnectedStripeAccountRepository accounts;
    private StripeCheckoutService checkout;
    private StallReadinessClient readiness;
    private PurchaseCheckoutController controller;

    @BeforeEach
    void setUp() {
        accounts = mock(ConnectedStripeAccountRepository.class);
        checkout = mock(StripeCheckoutService.class);
        readiness = mock(StallReadinessClient.class);
        when(readiness.checkSale(eq(STALL), anyString(), anyLong())).thenReturn(StallReadinessClient.Readiness.ready());
        controller = new PurchaseCheckoutController(accounts, checkout, readiness);
        ReflectionTestUtils.setField(controller, "maxAmountMinor", 1_000_000L);
        ConnectedStripeAccount account = mock(ConnectedStripeAccount.class);
        when(account.isChargesEnabled()).thenReturn(true);
        when(account.getDefaultCurrency()).thenReturn("gbp");
        when(account.getStripeAccountId()).thenReturn("acct_1");
        when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.of(account));
        when(checkout.createPurchaseSession(anyString(), anyInt(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(StripeCheckoutSession.builder().sessionUrl("https://checkout.stripe.com/c/cs_test").build());
    }

    @Test
    void refusesAGuestWithoutCreatingASession() {
        ResponseEntity<?> response = controller.start(
                new PurchaseCheckoutController.PurchaseRequest(STALL, 2500L, null));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals(Map.of("error", "Create a wallet first to buy a voucher by card"), response.getBody());
        verify(checkout, never()).createPurchaseSession(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesAMalformedBuyerRatherThanTreatingItAsAGuest() {
        ResponseEntity<?> response = controller.start(
                new PurchaseCheckoutController.PurchaseRequest(STALL, 2500L, "not-a-key"));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        verify(checkout, never()).createPurchaseSession(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void startsASessionForABuyerWithAWallet() {
        ResponseEntity<?> response = controller.start(
                new PurchaseCheckoutController.PurchaseRequest(STALL, 2500L, BUYER.toUpperCase()));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(checkout).createPurchaseSession(anyString(), eq(2500), eq("Voucher"), eq("gbp"), eq("acct_1"),
                eq(null), eq(Map.of("buyer_pubkey", BUYER)));
    }

    @Test
    void aStripeOutageAnswersAFast503WithRetryAfter() {
        // payment-adapter#274 (S9): an outage is not the buyer's mistake. 503 + Retry-After says
        // "try again shortly"; no session exists, so a retry cannot double-charge.
        when(checkout.createPurchaseSession(anyString(), anyInt(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new StripeCheckoutCreationException("q", "gbp",
                        new com.stripe.exception.ApiConnectionException("timed out")));

        ResponseEntity<?> response = controller.start(
                new PurchaseCheckoutController.PurchaseRequest(STALL, 2500L, BUYER));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
        assertEquals(Map.of("error", PurchaseCheckoutController.CARD_PAYMENTS_UNAVAILABLE), response.getBody());
    }

    @Test
    void aStripeRefusalStaysA400() {
        // Validation refusals keep today's 400: retrying unchanged cannot help.
        when(checkout.createPurchaseSession(anyString(), anyInt(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new StripeCheckoutCreationException("q", "gbp",
                        new com.stripe.exception.InvalidRequestException("bad", null, null, null, 400, null)));

        ResponseEntity<?> response = controller.start(
                new PurchaseCheckoutController.PurchaseRequest(STALL, 2500L, BUYER));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // payment-adapter#293: a ready stall still gets its session, and readiness was asked first.
    @Test
    void aReadyStallGetsASession() {
        ResponseEntity<?> response = controller.start(
                new PurchaseCheckoutController.PurchaseRequest(STALL, 2500L, BUYER));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        // Asked for this sale: the account's currency and the amount (gateway-customer#317).
        verify(readiness).checkSale(STALL, "gbp", 2500L);
    }

    // gateway-customer#317: a Free stall at its allowance, or one this amount would take past it, is
    // a 409 with the buyer's words and the code, a sales-limit metric, and Stripe is never called.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            StallReadinessClient.SALES_LIMIT_REACHED, StallReadinessClient.SALES_LIMIT_EXCEEDED})
    void aStallOverItsFreeAllowanceIsA409AndNoSession(String reason) {
        when(readiness.checkSale(eq(STALL), anyString(), anyLong()))
                .thenReturn(StallReadinessClient.Readiness.notReady(reason));

        ResponseEntity<?> response = controller.start(
                new PurchaseCheckoutController.PurchaseRequest(STALL, 2500L, BUYER));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(Map.of("error", PurchaseCheckoutController.salesLimitMessage(reason), "reason", reason),
                response.getBody());
        verify(checkout, never()).createPurchaseSession(any(), any(), any(), any(), any(), any(), any());
    }

    // Card payments are Essentials and above: a Starter stall is a 409 naming its plan, and Stripe
    // is never called, so a direct call to this public endpoint cannot sell by card either.
    @Test
    void aStallWithNoPaidPlanIsA409AndNoSession() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller, "setMeterRegistry", registry);
        when(readiness.checkSale(eq(STALL), anyString(), anyLong()))
                .thenReturn(StallReadinessClient.Readiness.notReady(StallReadinessClient.NOT_IN_PLAN));

        ResponseEntity<?> response = controller.start(
                new PurchaseCheckoutController.PurchaseRequest(STALL, 2500L, BUYER));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(Map.of("error", PurchaseCheckoutController.NOT_IN_PLAN, "reason", StallReadinessClient.NOT_IN_PLAN),
                response.getBody());
        assertTrue(PurchaseCheckoutController.NOT_IN_PLAN.endsWith("Nothing was charged."));
        verify(checkout, never()).createPurchaseSession(any(), any(), any(), any(), any(), any(), any());
        // Review regression: counted as a plan refusal, never as a stall that is not enrolled.
        assertEquals(1.0, registry.find("imani.payments").tag("status", "refused").tag("reason", "not_in_plan")
                .counter().count());
        assertEquals(null, registry.find("imani.payments").tag("status", "refused").tag("reason", "not_ready").counter());
    }

    // The two codes read differently: "no sales left" is not "try a smaller amount".
    @Test
    void theSalesLimitMessagesSayWhatTheBuyerCanDo() {
        assertTrue(PurchaseCheckoutController.salesLimitMessage(StallReadinessClient.SALES_LIMIT_REACHED)
                .contains("reached its sales limit"));
        assertTrue(PurchaseCheckoutController.salesLimitMessage(StallReadinessClient.SALES_LIMIT_EXCEEDED)
                .contains("smaller amount"));
        assertTrue(PurchaseCheckoutController.SALES_LIMIT_REACHED.endsWith("Nothing was charged."));
        assertTrue(PurchaseCheckoutController.SALES_LIMIT_EXCEEDED.endsWith("Nothing was charged."));
    }

    // payment-adapter#293: every not-ready reason purchase-issuer reports is a 409 naming it,
    // and Stripe is never called, so nobody pays for a coupon that cannot be issued.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            StallReadinessClient.NOT_ENROLLED,
            "validity_never_expires", "validity_outlives_delegation", "validity_unknown"})
    void aNotReadyStallIsA409WithItsReasonAndNoSession(String reason) {
        when(readiness.checkSale(eq(STALL), anyString(), anyLong()))
                .thenReturn(StallReadinessClient.Readiness.notReady(reason));

        ResponseEntity<?> response = controller.start(
                new PurchaseCheckoutController.PurchaseRequest(STALL, 2500L, BUYER));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(Map.of("error", PurchaseCheckoutController.STALL_NOT_READY, "reason", reason), response.getBody());
        verify(checkout, never()).createPurchaseSession(any(), any(), any(), any(), any(), any(), any());
    }

    // payment-adapter#293: readiness unknown (PI down, timed out, a bad answer, unconfigured)
    // fails CLOSED with a 503, and Stripe is never called.
    @Test
    void unknownReadinessIsA503AndNoSession() {
        when(readiness.checkSale(eq(STALL), anyString(), anyLong()))
                .thenReturn(StallReadinessClient.Readiness.unknown());

        ResponseEntity<?> response = controller.start(
                new PurchaseCheckoutController.PurchaseRequest(STALL, 2500L, BUYER));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(PurchaseCheckoutController.RETRY_AFTER_SECONDS, response.getHeaders().getFirst("Retry-After"));
        assertEquals(Map.of("error", PurchaseCheckoutController.CARD_SALES_UNAVAILABLE), response.getBody());
        verify(checkout, never()).createPurchaseSession(any(), any(), any(), any(), any(), any(), any());
    }

    // payment-adapter#293: the cheap local refusals come first, so a bad request never costs a PI call.
    @Test
    void aStallThatCannotChargeIsRefusedWithoutAskingReadiness() {
        ConnectedStripeAccount off = mock(ConnectedStripeAccount.class);
        when(off.isChargesEnabled()).thenReturn(false);
        when(accounts.findByMerchantPubkey(STALL)).thenReturn(Optional.of(off));

        controller.start(new PurchaseCheckoutController.PurchaseRequest(STALL, 2500L, BUYER));

        verify(readiness, never()).checkSale(any(), any(), anyLong());
        verify(readiness, never()).check(any());
    }

    // imani-wallet#410: a gift is paid by its draft id alone; nothing else in the body is read.
    @Test
    void aDraftIdIsTheWholeRequestAndGoesToTheGiftingService() {
        var gifting = org.mockito.Mockito.mock(xyz.tcheeric.payment.adapter.stripe.webhook.gifting.GiftingService.class);
        java.util.UUID id = java.util.UUID.randomUUID();
        when(gifting.pay(id, null)).thenReturn(new xyz.tcheeric.payment.adapter.stripe.webhook.gifting.GiftingService.Answer(
                HttpStatus.OK, Map.of("checkoutUrl", "https://checkout.stripe.com/c/cs_g", "reference", id.toString())));
        controller.setGifting(gifting);

        ResponseEntity<?> response = controller.start(
                new PurchaseCheckoutController.PurchaseRequest(STALL, 999_999L, "e".repeat(64), id.toString()));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(gifting).pay(id, null);
        verify(checkout, never()).createPurchaseSession(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aDraftIdWithoutGiftingWiredIsRefusedNotTreatedAsAWalletPurchase() {
        ResponseEntity<?> response = controller.start(
                new PurchaseCheckoutController.PurchaseRequest(STALL, 2500L, BUYER, java.util.UUID.randomUUID().toString()));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(checkout, never()).createPurchaseSession(any(), any(), any(), any(), any(), any(), any());
    }
}
