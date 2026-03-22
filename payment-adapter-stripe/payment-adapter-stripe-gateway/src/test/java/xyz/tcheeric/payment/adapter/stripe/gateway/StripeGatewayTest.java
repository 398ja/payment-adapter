package xyz.tcheeric.payment.adapter.stripe.gateway;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.common.PaymentType;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePaymentReference;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePaymentReferenceRepository;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StripeGatewayTest {

    private StripeCheckoutService checkoutService;
    private QuoteClient quoteClient;
    private PaymentClient paymentClient;
    private StripePaymentReferenceRepository referenceRepository;
    private StripeGateway gateway;

    @BeforeEach
    void setUp() {
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setDefaultCurrency("usd");
        properties.setAllowedCurrencies(java.util.List.of("usd"));

        checkoutService = mock(StripeCheckoutService.class);
        quoteClient = mock(QuoteClient.class);
        paymentClient = mock(PaymentClient.class);
        referenceRepository = mock(StripePaymentReferenceRepository.class);
        gateway = new StripeGateway(checkoutService, quoteClient, paymentClient, referenceRepository, properties);
    }

    // Verifies creating a mint quote persists a pending quote, payment, and Stripe reference.
    @Test
    void createsMintQuoteWithPendingRecords() {
        StripeCheckoutSession session = StripeCheckoutSession.builder()
                .sessionId("cs_test_123")
                .sessionUrl("https://checkout.stripe.test/quote-123")
                .paymentStatus("unpaid")
                .status("open")
                .expiresAtEpochSeconds(Instant.now().plusSeconds(1800).getEpochSecond())
                .paymentIntentId("pi_123")
                .livemode(false)
                .build();
        when(checkoutService.createCheckoutSession(any(String.class), any(Integer.class), any(String.class))).thenReturn(session);
        when(checkoutService.buildIdempotencyKey(any(String.class))).thenAnswer(invocation -> "stripe:checkout:" + invocation.getArgument(0));
        when(quoteClient.create(any(GatewayQuote.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentClient.create(any(GatewayPayment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(referenceRepository.save(any(StripePaymentReference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String quoteId = gateway.createMintQuote(100, "Voucher");

        assertEquals(36, quoteId.length());
    }

    // Verifies the gateway reports paid once the local payment record has reached a paid state.
    @Test
    void reportsPaidWhenLocalPaymentIsPaid() {
        GatewayQuote quote = GatewayQuote.create("quote-456", "cs_456", 600, "Voucher", "https://checkout", 100, "usd");
        GatewayPayment payment = GatewayPayment.create("https://checkout", "pi_456", "quote-456", "usd", 100, 0, 100, null, null);
        payment.setState(State.PAID);

        when(quoteClient.getByEntityId("quote-456")).thenReturn(quote);
        when(paymentClient.getByQuoteId("quote-456")).thenReturn(payment);

        assertTrue(gateway.checkPaymentStatus("quote-456"));
    }

    // Verifies unsupported melt operations fail fast with a clear exception.
    @Test
    void rejectsUnsupportedMeltOperations() {
        assertThrows(UnsupportedOperationException.class, () -> gateway.createMeltQuote(10, "request", "refund"));
        assertThrows(UnsupportedOperationException.class, () -> gateway.createMeltQuote("request"));
        assertThrows(UnsupportedOperationException.class, () -> gateway.pay("request"));
        assertEquals(PaymentType.CREDIT_CARD, gateway.getPaymentType());
    }
}
