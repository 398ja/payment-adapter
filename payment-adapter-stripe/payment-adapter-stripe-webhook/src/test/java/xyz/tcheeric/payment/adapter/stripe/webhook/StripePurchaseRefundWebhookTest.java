package xyz.tcheeric.payment.adapter.stripe.webhook;

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
import xyz.tcheeric.payment.adapter.core.model.repository.ProcessedStripeWebhookEventRepository;
import xyz.tcheeric.payment.adapter.core.model.repository.StripePaymentReferenceRepository;
import xyz.tcheeric.payment.adapter.stripe.webhook.service.StripeWebhookSignatureVerifier;
import xyz.tcheeric.payment.adapter.stripe.webhook.spi.StripePurchaseListener;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.spi.WebhookResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * payment-adapter#267: a refund or dispute on a coupon purchase (a direct charge, so the event
 * carries {@code account}) is handed to the purchase listener and acknowledged, never answered
 * 500 by the mint path's missing {@code StripePaymentReference} (card atomicity audit S8, S12).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StripePurchaseRefundWebhookTest {

    private static final String ACCOUNT = "acct_1MerchantXyz";

    @Mock private ProcessedStripeWebhookEventRepository processedEventRepository;
    @Mock private StripePaymentReferenceRepository paymentReferenceRepository;
    @Mock private QuoteClient quoteClient;
    @Mock private PaymentClient paymentClient;
    @Mock private StripeWebhookSignatureVerifier signatureVerifier;
    @Mock private StripePurchaseListener purchaseListener;

    private StripeWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StripeWebhookHandler(processedEventRepository, paymentReferenceRepository,
                quoteClient, paymentClient, signatureVerifier);
        when(processedEventRepository.findById(any())).thenReturn(Optional.empty());
        when(processedEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static StripeWebhookPayload reversal(String type) {
        return StripeWebhookPayload.builder()
                .eventId("evt_" + type)
                .eventType(type)
                .rawPayload("{\"id\":\"evt_" + type + "\"}")
                .paymentIntentId("pi_purchase")
                .chargeId("ch_purchase")
                .amountTotal(500)
                .refundedAmountMinor(type.equals("charge.refunded") ? 200 : null)
                .currency("gbp")
                .connectedAccountId(ACCOUNT)
                .build();
    }

    private StripePurchaseListener.ReversedPurchase captured() throws Exception {
        ArgumentCaptor<StripePurchaseListener.ReversedPurchase> captor =
                ArgumentCaptor.forClass(StripePurchaseListener.ReversedPurchase.class);
        verify(purchaseListener).onPurchaseReversed(captor.capture());
        return captor.getValue();
    }

    @Test
    void aRefundOnAPurchaseReachesTheListenerWithTheCumulativeRefund() throws Exception {
        // S8: the refund is recorded against the purchase by its pi_, with Stripe's cumulative
        // amount_refunded, so backed = paid - refunded is derivable.
        handler.setPurchaseListener(purchaseListener);

        WebhookResult result = handler.handle(reversal("charge.refunded"));

        StripePurchaseListener.ReversedPurchase r = captured();
        assertEquals("pi_purchase", r.paymentIntentId());
        assertEquals(200L, r.refundedAmountMinor());
        assertEquals(false, r.disputed());
        assertTrue(result.processed());
    }

    @Test
    void aDisputeOnAPurchaseReachesTheListenerAsADispute() throws Exception {
        // A dispute is recorded as a dispute, never as a refunded amount: it can be won.
        handler.setPurchaseListener(purchaseListener);

        handler.handle(reversal("charge.dispute.created"));

        StripePurchaseListener.ReversedPurchase r = captured();
        assertTrue(r.disputed());
        assertNull(r.refundedAmountMinor());
    }

    @Test
    void neverTouchesTheMintPathsPaymentReferenceForAPurchase() throws Exception {
        // The mint path's look-up is what threw "payment reference not found" -> 500 (#267).
        handler.setPurchaseListener(purchaseListener);

        handler.handle(reversal("charge.refunded"));
        handler.handle(reversal("charge.dispute.created"));

        verifyNoInteractions(paymentReferenceRepository, quoteClient, paymentClient);
    }

    @Test
    void isAcknowledgedEvenWithNoListenerConfigured() throws Exception {
        // Nothing here could ever act on it; a retry would only push Stripe toward disabling
        // the endpoint that also carries the checkout.session.* events.
        WebhookResult result = handler.handle(reversal("charge.refunded"));

        assertTrue(result.processed());
    }

    @Test
    void isRefusedSoStripeRetriesWhenRecordingFails() throws Exception {
        // A refund that was not recorded must not be marked processed: the payment could
        // still be minted. Non-2xx makes Stripe redeliver.
        handler.setPurchaseListener(purchaseListener);
        doThrow(new IllegalStateException("db down")).when(purchaseListener).onPurchaseReversed(any());

        assertThrows(WebhookProcessingException.class, () -> handler.handle(reversal("charge.refunded")));
    }
}
