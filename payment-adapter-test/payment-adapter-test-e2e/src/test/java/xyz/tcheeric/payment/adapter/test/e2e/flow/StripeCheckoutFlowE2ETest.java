package xyz.tcheeric.payment.adapter.test.e2e.flow;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.common.PaymentType;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.ProcessedStripeWebhookEvent;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripePaymentReference;
import xyz.tcheeric.payment.adapter.core.model.entity.stripe.StripeWebhookProcessingStatus;
import xyz.tcheeric.payment.adapter.stripe.gateway.StripeGateway;
import xyz.tcheeric.payment.adapter.stripe.gateway.config.StripeGatewayProperties;
import xyz.tcheeric.payment.adapter.stripe.gateway.model.StripeCheckoutSession;
import xyz.tcheeric.payment.adapter.stripe.gateway.service.StripeCheckoutService;
import xyz.tcheeric.payment.adapter.stripe.webhook.StripeWebhookHandler;
import xyz.tcheeric.payment.adapter.stripe.webhook.StripeWebhookPayload;
import xyz.tcheeric.payment.adapter.test.e2e.BaseE2ETest;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StripeCheckoutFlowE2ETest extends BaseE2ETest {

    @BeforeEach
    void setUp() {
        cleanDatabase();
        configureGatewayClientBaseUrl();
    }

    // Verifies the Stripe gateway creates quote, payment, and Stripe reference records through the real REST layer.
    @Test
    void createMintQuote_persistsQuotePaymentAndStripeReference() {
        StripeGateway gateway = createGateway(checkoutSession("cs_test_e2e_1", "pi_test_e2e_1"));

        String quoteId = gateway.createMintQuote(2100, "Stripe checkout e2e");

        GatewayQuote quote = restTemplate.getForObject(
                url("/quote/search/findByQuoteId?quoteId=" + quoteId),
                GatewayQuote.class
        );
        GatewayPayment payment = restTemplate.getForObject(
                url("/payment/search/findByQuoteId?quoteId=" + quoteId),
                GatewayPayment.class
        );
        StripePaymentReference paymentReference = stripePaymentReferenceRepository.findByQuoteId(quoteId).orElseThrow();

        assertThat(quote).isNotNull();
        assertThat(quote.getQuoteId()).isEqualTo(quoteId);
        assertThat(quote.getInvoiceId()).isEqualTo("cs_test_e2e_1");
        assertThat(quote.getAmount()).isEqualTo(2100);
        assertThat(quote.getUnit()).isEqualTo("usd");
        assertThat(quote.getRequest()).isEqualTo("https://checkout.test/cs_test_e2e_1");
        assertThat(quote.getState()).isEqualTo(State.PENDING);

        assertThat(payment).isNotNull();
        assertThat(payment.getQuoteId()).isEqualTo(quoteId);
        assertThat(payment.getState()).isEqualTo(State.PENDING);
        assertThat(payment.getGatewayId()).isEqualTo("stripe");
        assertThat(payment.getPaymentType()).isEqualTo(PaymentType.CREDIT_CARD);
        assertThat(payment.getIdempotencyKey()).isEqualTo("stripe:checkout:" + quoteId);

        assertThat(paymentReference.getCheckoutSessionId()).isEqualTo("cs_test_e2e_1");
        assertThat(paymentReference.getPaymentIntentId()).isEqualTo("pi_test_e2e_1");
        assertThat(paymentReference.getStripeStatus()).isEqualTo("open");
        assertThat(paymentReference.getLastEventId()).isNull();
    }

    // Verifies a parsed Stripe webhook settles the persisted quote and payment and records the processed event.
    @Test
    void checkoutWebhook_fullRoundTrip_marksQuoteAndPaymentPaid() {
        StripeGateway gateway = createGateway(checkoutSession("cs_test_e2e_2", "pi_test_e2e_2"));
        String quoteId = gateway.createMintQuote(3300, "Stripe settlement e2e");

        StripeWebhookHandler webhookHandler = new StripeWebhookHandler(
                processedStripeWebhookEventRepository,
                stripePaymentReferenceRepository,
                new QuoteClient(),
                new PaymentClient(),
                (rawPayload, signatureHeader) -> {
                }
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.addHeader("Stripe-Signature", "t=123,v1=test");
        request.setContent(stripeCheckoutCompletedPayload(quoteId, "cs_test_e2e_2", "pi_test_e2e_2", 3300)
                .getBytes(StandardCharsets.UTF_8));

        StripeWebhookPayload payload = webhookHandler.parsePayload(request);
        webhookHandler.validateSignature(payload, request);
        WebhookResult result = webhookHandler.handle(payload);

        GatewayQuote quote = restTemplate.getForObject(
                url("/quote/search/findByQuoteId?quoteId=" + quoteId),
                GatewayQuote.class
        );
        GatewayPayment payment = restTemplate.getForObject(
                url("/payment/search/findByQuoteId?quoteId=" + quoteId),
                GatewayPayment.class
        );
        StripePaymentReference paymentReference = stripePaymentReferenceRepository.findByQuoteId(quoteId).orElseThrow();
        ProcessedStripeWebhookEvent processedEvent = processedStripeWebhookEventRepository.findById("evt_test_e2e_2")
                .orElseThrow();

        assertThat(result.processed()).isTrue();
        assertThat(result.newState()).isEqualTo(State.PAID);
        assertThat(result.paymentId()).isEqualTo("pi_test_e2e_2");

        assertThat(quote).isNotNull();
        assertThat(quote.getState()).isEqualTo(State.PAID);

        assertThat(payment).isNotNull();
        assertThat(payment.getState()).isEqualTo(State.PAID);
        assertThat(payment.getPaymentId()).isEqualTo("pi_test_e2e_2");
        assertThat(payment.getWebhookProcessedAt()).isNotNull();
        assertThat(payment.getPaidDate()).isNotNull();

        assertThat(paymentReference.getPaymentIntentId()).isEqualTo("pi_test_e2e_2");
        assertThat(paymentReference.getStripeStatus()).isEqualTo("paid");
        assertThat(paymentReference.getLastEventId()).isEqualTo("evt_test_e2e_2");

        assertThat(processedEvent.getProcessingStatus()).isEqualTo(StripeWebhookProcessingStatus.PROCESSED);
        assertThat(processedEvent.getProcessedAt()).isNotNull();
        assertThat(processedEvent.getLastError()).isNull();
    }

    private StripeGateway createGateway(StripeCheckoutSession checkoutSession) {
        StripeCheckoutService checkoutService = mock(StripeCheckoutService.class);
        when(checkoutService.createCheckoutSession(anyString(), anyInt(), anyString()))
                .thenReturn(checkoutSession);
        when(checkoutService.buildIdempotencyKey(anyString()))
                .thenAnswer(invocation -> "stripe:checkout:" + invocation.getArgument(0, String.class));

        return new StripeGateway(
                checkoutService,
                new QuoteClient(),
                new PaymentClient(),
                stripePaymentReferenceRepository,
                stripeProperties()
        );
    }

    private StripeCheckoutSession checkoutSession(String sessionId, String paymentIntentId) {
        return StripeCheckoutSession.builder()
                .sessionId(sessionId)
                .sessionUrl("https://checkout.test/" + sessionId)
                .paymentStatus("open")
                .status("open")
                .paymentIntentId(paymentIntentId)
                .expiresAtEpochSeconds(Instant.now().plusSeconds(1800).getEpochSecond())
                .livemode(false)
                .build();
    }

    private StripeGatewayProperties stripeProperties() {
        StripeGatewayProperties properties = new StripeGatewayProperties();
        properties.setDefaultCurrency("usd");
        properties.setAllowedCurrencies(List.of("usd"));
        properties.setMinAmountMinor(1);
        properties.setMaxAmountMinor(1_000_000);
        return properties;
    }

    private String stripeCheckoutCompletedPayload(String quoteId,
                                                  String checkoutSessionId,
                                                  String paymentIntentId,
                                                  int amountTotal) {
        return """
                {
                  "id": "evt_test_e2e_2",
                  "type": "checkout.session.completed",
                  "created": %d,
                  "livemode": false,
                  "data": {
                    "object": {
                      "id": "%s",
                      "payment_intent": "%s",
                      "amount_total": %d,
                      "currency": "usd",
                      "status": "complete",
                      "payment_status": "paid",
                      "metadata": {
                        "quote_id": "%s"
                      }
                    }
                  }
                }
                """.formatted(
                Instant.now().getEpochSecond(),
                checkoutSessionId,
                paymentIntentId,
                amountTotal,
                quoteId
        );
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
