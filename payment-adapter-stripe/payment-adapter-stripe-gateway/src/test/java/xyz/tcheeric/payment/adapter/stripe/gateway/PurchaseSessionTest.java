package xyz.tcheeric.payment.adapter.stripe.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.exception.StripeValidationException;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutRequest;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutClient;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A coupon purchase is a DIRECT charge, and these tests exist to keep it one.
 *
 * <p>The distinction is not a detail of fee routing. On a platform charge the
 * platform holds the funds and is merchant of record, which would make Imani a
 * holder of customer money and put it inside the FCA safeguarding perimeter.
 * The no-float position is the reason the whole model works, so "did this
 * session name the connected account" is a correctness assertion rather than a
 * configuration one.
 */
class PurchaseSessionTest {

    private static final String ACCOUNT = "acct_1MerchantXyz";

    private StripeCheckoutClient checkoutClient;
    private StripeCheckoutService checkoutService;
    private StripeGatewayProperties properties;

    @BeforeEach
    void setUp() {
        properties = new StripeGatewayProperties();
        properties.setSuccessUrl("https://wallet.example/settings/payments?stripe=success");
        properties.setCancelUrl("https://wallet.example/settings/payments?stripe=cancel");
        properties.setDefaultCurrency("USD");
        properties.setAllowedCurrencies(java.util.List.of("usd", "gbp"));
        properties.setCheckoutExpirySeconds(900);
        properties.setMinAmountMinor(10);
        properties.setMaxAmountMinor(100000);
        checkoutClient = mock(StripeCheckoutClient.class);
        checkoutService = new StripeCheckoutService(properties, checkoutClient);
        when(checkoutClient.createCheckoutSession(any(StripeCheckoutRequest.class)))
                .thenReturn(StripeCheckoutSession.builder().sessionId("cs_purchase").build());
    }

    private StripeCheckoutRequest capture() {
        ArgumentCaptor<StripeCheckoutRequest> captor = ArgumentCaptor.forClass(StripeCheckoutRequest.class);
        verify(checkoutClient).createCheckoutSession(captor.capture());
        return captor.getValue();
    }

    @Test
    void chargesTheConnectedAccountRatherThanThePlatform() {
        checkoutService.createPurchaseSession(
                "q-1", 2500, "Voucher", "GBP", ACCOUNT, 125L, java.util.Map.of());

        StripeCheckoutRequest request = capture();
        assertEquals(ACCOUNT, request.getConnectedAccountId());
        assertTrue(request.isDirectCharge(), "a purchase must never be a platform charge");
        assertEquals(125L, request.getApplicationFeeMinor());
    }

    @Test
    void theMintQuoteFlowStaysAPlatformCharge() {
        // The other half of the same guarantee. Imani sells its own product on
        // this path, so the platform SHOULD be merchant of record, and the
        // correction must not have quietly changed it.
        checkoutService.createCheckoutSession("q-2", 500, "Mint quote");

        StripeCheckoutRequest request = capture();
        assertNull(request.getConnectedAccountId());
        assertFalse(request.isDirectCharge());
        assertNull(request.getApplicationFeeMinor());
    }

    @Test
    void usesTheCallersCurrencyRatherThanThePlatformDefault() {
        // A stall issues in its own currency. Falling back to the platform
        // default would price a purchase in the wrong money.
        checkoutService.createPurchaseSession(
                "q-3", 2500, "Voucher", "gbp", ACCOUNT, null, null);

        assertEquals("gbp", capture().getCurrency());
    }

    @Test
    void carriesMetadataForTheBuyer() {
        // How the webhook learns who to issue the coupon to, without a second
        // lookup and without the buyer typing a pubkey into a Stripe form.
        checkoutService.createPurchaseSession(
                "q-4", 2500, "Voucher", "GBP", ACCOUNT, null,
                java.util.Map.of("buyer_pubkey", "a".repeat(64)));

        assertEquals("a".repeat(64), capture().getMetadata().get("buyer_pubkey"));
    }

    @Test
    void refusesAPurchaseWithNoConnectedAccount() {
        // The failure this whole change exists to prevent: a purchase that
        // silently becomes a platform charge.
        assertThrows(StripeValidationException.class, () -> checkoutService.createPurchaseSession(
                "q-5", 2500, "Voucher", "GBP", null, null, null));
        assertThrows(StripeValidationException.class, () -> checkoutService.createPurchaseSession(
                "q-6", 2500, "Voucher", "GBP", "  ", null, null));
    }

    @Test
    void refusesAFeeThatWouldLeaveTheStallNothing() {
        assertThrows(StripeValidationException.class, () -> checkoutService.createPurchaseSession(
                "q-7", 2500, "Voucher", "GBP", ACCOUNT, 2500L, null));
        assertThrows(StripeValidationException.class, () -> checkoutService.createPurchaseSession(
                "q-8", 2500, "Voucher", "GBP", ACCOUNT, 9999L, null));
        assertThrows(StripeValidationException.class, () -> checkoutService.createPurchaseSession(
                "q-9", 2500, "Voucher", "GBP", ACCOUNT, -1L, null));
    }

    @Test
    void refusesACurrencyTheDeploymentDoesNotAllow() {
        assertThrows(StripeValidationException.class, () -> checkoutService.createPurchaseSession(
                "q-10", 2500, "Voucher", "JPY", ACCOUNT, null, null));
    }

    @Test
    void keepsTheAmountBoundsThePlatformPathHas() {
        assertThrows(StripeValidationException.class, () -> checkoutService.createPurchaseSession(
                "q-11", 1, "Voucher", "GBP", ACCOUNT, null, null));
        assertThrows(StripeValidationException.class, () -> checkoutService.createPurchaseSession(
                "q-12", 999999, "Voucher", "GBP", ACCOUNT, null, null));
    }

    @Test
    void sendsTheBuyerToTheBuyerReturnPagesWhenConfigured() {
        // The global success/cancel URLs land on the MERCHANT settings screen
        // (imani-wallet#267 item 3). A buyer must come back to /buy/done.
        properties.setPurchaseSuccessUrl("https://wallet.example/buy/done?session_id={CHECKOUT_SESSION_ID}");
        properties.setPurchaseCancelUrl("https://wallet.example/buy/cancelled");
        checkoutService.createPurchaseSession("q-13", 2500, "Voucher", "GBP", ACCOUNT, null, null);

        StripeCheckoutRequest request = capture();
        assertEquals("https://wallet.example/buy/done?session_id={CHECKOUT_SESSION_ID}", request.getSuccessUrl());
        assertEquals("https://wallet.example/buy/cancelled", request.getCancelUrl());
    }

    @Test
    void fallsBackToTheGlobalReturnUrlsWhenNoBuyerOnesAreSet() {
        checkoutService.createPurchaseSession("q-14", 2500, "Voucher", "GBP", ACCOUNT, null, null);

        StripeCheckoutRequest request = capture();
        assertEquals("https://wallet.example/settings/payments?stripe=success", request.getSuccessUrl());
        assertEquals("https://wallet.example/settings/payments?stripe=cancel", request.getCancelUrl());
    }

    @Test
    void theMintQuoteFlowKeepsTheGlobalReturnUrls() {
        properties.setPurchaseSuccessUrl("https://wallet.example/buy/done");
        checkoutService.createCheckoutSession("q-15", 500, "Mint quote");

        StripeCheckoutRequest request = capture();
        assertNull(request.getSuccessUrl(), "null means the client uses stripe.success-url");
        assertNull(request.getCancelUrl());
    }
}
