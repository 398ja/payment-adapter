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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
        when(readiness.check(STALL)).thenReturn(StallReadinessClient.Readiness.ready());
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
        verify(readiness).check(STALL);
    }

    // payment-adapter#293: every not-ready reason purchase-issuer reports is a 409 naming it,
    // and Stripe is never called, so nobody pays for a coupon that cannot be issued.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            StallReadinessClient.NOT_ENROLLED,
            "validity_never_expires", "validity_outlives_delegation", "validity_unknown"})
    void aNotReadyStallIsA409WithItsReasonAndNoSession(String reason) {
        when(readiness.check(STALL)).thenReturn(StallReadinessClient.Readiness.notReady(reason));

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
        when(readiness.check(STALL)).thenReturn(StallReadinessClient.Readiness.unknown());

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

        verify(readiness, never()).check(any());
    }
}
