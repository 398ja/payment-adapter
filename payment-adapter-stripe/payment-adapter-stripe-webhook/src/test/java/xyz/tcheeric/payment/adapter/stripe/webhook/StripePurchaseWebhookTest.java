package xyz.tcheeric.payment.adapter.stripe.webhook;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedStripeWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePaymentReferenceRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.service.StripeWebhookSignatureVerifier;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A paid coupon purchase leaves this service through a listener.
 *
 * <p>Two consequences share one event type. A platform charge settles a mint
 * quote, which is this service's own business; a direct charge is a stall
 * selling a coupon, which is somebody else's. Getting the branch wrong is not a
 * cosmetic error: a purchase routed down the mint path dies on a missing
 * {@code GatewayQuote}, and a mint quote routed to the listener would issue a
 * coupon nobody bought.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StripePurchaseWebhookTest {

    private static final String ACCOUNT = "acct_1MerchantXyz";
    private static final String BUYER = "a".repeat(64);

    private StripeWebhookHandler handler;

    @Mock private ProcessedStripeWebhookEventRepository processedEventRepository;
    @Mock private StripePaymentReferenceRepository paymentReferenceRepository;
    @Mock private QuoteClient quoteClient;
    @Mock private PaymentClient paymentClient;
    @Mock private StripeWebhookSignatureVerifier signatureVerifier;
    @Mock private StripePurchaseListener purchaseListener;
    @Mock private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new StripeWebhookHandler(
                processedEventRepository, paymentReferenceRepository,
                quoteClient, paymentClient, signatureVerifier);
        when(processedEventRepository.findById(any())).thenReturn(Optional.empty());
        when(processedEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private StripeWebhookPayload paidPurchase() {
        return StripeWebhookPayload.builder()
                .eventId("evt_purchase_1")
                .eventType("checkout.session.completed")
                // The handler hashes the raw body for its processed-event
                // record, so a fixture without one fails before reaching the
                // branch under test.
                .rawPayload("{\"id\":\"evt_purchase_1\"}")
                .checkoutSessionId("cs_purchase")
                .paymentIntentId("pi_purchase")
                .amountTotal(2500)
                .currency("gbp")
                .paymentStatus("paid")
                .connectedAccountId(ACCOUNT)
                .metadata(java.util.Map.of("buyer_pubkey", BUYER))
                .build();
    }

    @Test
    void handsAPaidPurchaseToTheListener() throws Exception {
        handler.setPurchaseListener(purchaseListener);

        WebhookResult result = handler.handle(paidPurchase());

        ArgumentCaptor<StripePurchaseListener.PaidPurchase> captor =
                ArgumentCaptor.forClass(StripePurchaseListener.PaidPurchase.class);
        verify(purchaseListener).onPurchasePaid(captor.capture());

        StripePurchaseListener.PaidPurchase purchase = captor.getValue();
        assertEquals(ACCOUNT, purchase.connectedAccountId());
        assertEquals(BUYER, purchase.metadata().get("buyer_pubkey"), "the buyer must survive the hop");
        assertEquals(2500L, purchase.amountMinor());
        assertEquals("gbp", purchase.currency());
        assertEquals(State.PAID, result.newState());
    }

    @Test
    void neverTouchesTheMintPathForAPurchase() throws Exception {
        // A purchase has no GatewayQuote. Reaching the mint path at all would
        // throw on a missing payment reference before any consequence ran.
        handler.setPurchaseListener(purchaseListener);

        handler.handle(paidPurchase());

        verifyNoInteractions(quoteClient);
        verifyNoInteractions(paymentClient);
        verify(paymentReferenceRepository, never()).save(any());
    }

    @Test
    void refusesAPurchaseWhenNothingIsListening() {
        // The customer has paid. Marking this processed would lose the debt
        // silently, so it fails and Stripe keeps retrying.
        WebhookProcessingException error =
                assertThrows(WebhookProcessingException.class, () -> handler.handle(paidPurchase()));

        assertTrue(error.getMessage().contains("no StripePurchaseListener"));
    }

    @Test
    void refusesTheEventWhenTheListenerRefuses() throws Exception {
        // "Paid means owed": if the obligation was not recorded, the event must
        // not be marked done.
        handler.setPurchaseListener(purchaseListener);
        doThrow(new IllegalStateException("obligation store down"))
                .when(purchaseListener).onPurchasePaid(any());

        assertThrows(WebhookProcessingException.class, () -> handler.handle(paidPurchase()));
    }

    @Test
    void defersAnUnpaidCompletedSession() throws Exception {
        // An async payment method still in flight. Issuing here would give a
        // coupon away before the money exists.
        handler.setPurchaseListener(purchaseListener);
        StripeWebhookPayload pending = paidPurchase().toBuilder()
                .paymentStatus("unpaid")
                .build();

        WebhookResult result = handler.handle(pending);

        verify(purchaseListener, never()).onPurchasePaid(any());
        assertEquals(State.PENDING, result.newState());
    }

    @Test
    void aPlatformChargeIsNotAPurchase() throws Exception {
        // The other half of the branch. No account means the mint path, which
        // here means failing on the missing reference rather than silently
        // issuing a coupon.
        handler.setPurchaseListener(purchaseListener);
        StripeWebhookPayload mintQuote = paidPurchase().toBuilder()
                .connectedAccountId(null)
                .build();
        when(paymentReferenceRepository.findByCheckoutSessionId(any())).thenReturn(Optional.empty());
        when(paymentReferenceRepository.findByPaymentIntentId(any())).thenReturn(Optional.empty());
        when(paymentReferenceRepository.findByChargeId(any())).thenReturn(Optional.empty());
        when(paymentReferenceRepository.findByQuoteId(any())).thenReturn(Optional.empty());

        assertThrows(WebhookProcessingException.class, () -> handler.handle(mintQuote));

        verify(purchaseListener, never()).onPurchasePaid(any());
    }

    @Test
    void parsesTheAccountAndMetadataFromTheWire() throws Exception {
        // `account` is top level, not inside data.object. Reading it from the
        // wrong place would make every purchase look like a platform charge.
        String rawBody = """
                {
                  "id":"evt_1",
                  "type":"checkout.session.completed",
                  "created":1710000000,
                  "livemode":false,
                  "account":"acct_1MerchantXyz",
                  "data":{"object":{
                    "id":"cs_1",
                    "payment_intent":"pi_1",
                    "amount_total":2500,
                    "currency":"gbp",
                    "payment_status":"paid",
                    "metadata":{"quote_id":"q-1","buyer_pubkey":"%s"}
                  }}
                }
                """.formatted(BUYER);
        when(request.getInputStream()).thenReturn(toServletInputStream(rawBody));

        StripeWebhookPayload payload = handler.parsePayload(request);

        assertEquals(ACCOUNT, payload.getConnectedAccountId());
        assertEquals(BUYER, payload.getMetadata().get("buyer_pubkey"));
        assertEquals("q-1", payload.getMetadata().get("quote_id"));
    }

    @Test
    void metadataIsEmptyRatherThanNullWhenAbsent() throws Exception {
        String rawBody = """
                {
                  "id":"evt_2",
                  "type":"checkout.session.completed",
                  "created":1710000000,
                  "data":{"object":{"id":"cs_2","payment_status":"paid"}}
                }
                """;
        when(request.getInputStream()).thenReturn(toServletInputStream(rawBody));

        assertTrue(handler.parsePayload(request).getMetadata().isEmpty());
    }

    private static ServletInputStream toServletInputStream(String body) {
        ByteArrayInputStream delegate = new ByteArrayInputStream(body.getBytes());
        return new ServletInputStream() {
            @Override public int read() {
                return delegate.read();
            }
            @Override public boolean isFinished() {
                return delegate.available() == 0;
            }
            @Override public boolean isReady() {
                return true;
            }
            @Override public void setReadListener(ReadListener readListener) {
                // Not used by the handler's blocking read.
            }
        };
    }
}
