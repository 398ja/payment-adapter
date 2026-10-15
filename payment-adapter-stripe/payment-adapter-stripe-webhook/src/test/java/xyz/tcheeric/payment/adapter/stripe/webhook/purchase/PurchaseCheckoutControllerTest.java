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
    private PurchaseCheckoutController controller;

    @BeforeEach
    void setUp() {
        accounts = mock(ConnectedStripeAccountRepository.class);
        checkout = mock(StripeCheckoutService.class);
        controller = new PurchaseCheckoutController(accounts, checkout);
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
}
