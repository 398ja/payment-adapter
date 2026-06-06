package xyz.tcheeric.payment.adapter.stripe.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.exception.StripeValidationException;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutRequest;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutClient;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StripeCheckoutServiceTest {

    private StripeCheckoutClient checkoutClient;
    private StripeCheckoutService checkoutService;

    @BeforeEach
    void setUp() {
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setDefaultCurrency("USD");
        properties.setAllowedCurrencies(java.util.List.of("usd"));
        properties.setCheckoutExpirySeconds(900);
        properties.setMinAmountMinor(10);
        properties.setMaxAmountMinor(1000);
        checkoutClient = mock(StripeCheckoutClient.class);
        checkoutService = new StripeCheckoutService(properties, checkoutClient);
    }

    // Verifies the checkout service normalizes the configured currency before creating a session.
    @Test
    void createsCheckoutSessionWithNormalizedCurrency() {
        when(checkoutClient.createCheckoutSession(any(StripeCheckoutRequest.class)))
                .thenAnswer(invocation -> {
                    StripeCheckoutRequest request = invocation.getArgument(0);
                    return StripeCheckoutSession.builder()
                            .sessionId("cs_123")
                            .sessionUrl("https://checkout.stripe.test")
                            .paymentStatus("unpaid")
                            .status("open")
                            .expiresAtEpochSeconds(InstantHolder.nowPlus(900))
                            .livemode(false)
                            .build();
                });

        StripeCheckoutSession session = checkoutService.createCheckoutSession("quote-123", 100, "Voucher");

        assertEquals("cs_123", session.getSessionId());
    }

    // Verifies the checkout service rejects amounts outside the configured bounds.
    @Test
    void rejectsAmountsOutsideConfiguredBounds() {
        assertThrows(StripeValidationException.class, () -> checkoutService.createCheckoutSession("quote-1", 1, "bad"));
        assertThrows(StripeValidationException.class, () -> checkoutService.createCheckoutSession("quote-2", 5000, "bad"));
    }

    private static final class InstantHolder {
        private static long nowPlus(int seconds) {
            return java.time.Instant.now().getEpochSecond() + seconds;
        }
    }
}
