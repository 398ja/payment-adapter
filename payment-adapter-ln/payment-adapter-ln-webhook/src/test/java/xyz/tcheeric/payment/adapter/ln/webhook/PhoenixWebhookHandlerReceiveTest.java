package xyz.tcheeric.payment.adapter.ln.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.Direction;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.webhook.exception.WebhookProcessingException;
import xyz.tcheeric.payment.adapter.webhook.forwarder.MintWebhookForwarder;
import xyz.tcheeric.payment.adapter.webhook.forwarder.PaymentNotification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Money arriving into a RECEIVE quote must be forwarded to the mint.
 *
 * <p>A {@code GatewayPayment} row is only created by {@code PhoenixdGateway.pay()}, which runs
 * when the gateway pays an invoice out. A mint quote is the opposite direction — someone pays the
 * invoice we issued — so no payment row exists, and requiring one made this handler work for melts
 * only.
 *
 * <p>Staging 2026-08-29: the quote sat {@code PAID} while the webhook died on "Payment not found",
 * the mint never recorded funding, and every issuance failed with {@code funding_required}.
 */
class PhoenixWebhookHandlerReceiveTest {

    private static final String QUOTE_ID = "e32c04c5-10ee-4ee9-aab0-d6bfccc3812d";
    private static final int AMOUNT_SAT = 30;
    private static final String PAYMENT_HASH = "hash51f93adf0823d6";

    private final QuoteClient quoteClient = Mockito.mock(QuoteClient.class);
    private final PaymentClient paymentClient = Mockito.mock(PaymentClient.class);
    private final MintWebhookForwarder mintForwarder = Mockito.mock(MintWebhookForwarder.class);

    private PhoenixWebhookHandler handler() {
        return new PhoenixWebhookHandler(quoteClient, paymentClient, mintForwarder);
    }


    /**
     * A missing payment surfaces as a 404 from the REST client, not as null. Stubbing null here
     * would test a condition the real client never produces — which is exactly how the first
     * attempt at this fix passed its tests and still failed on staging.
     */
    private void stubNoPaymentRecord() {
        when(paymentClient.getByQuoteId(QUOTE_ID))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                        org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));
    }

    private GatewayQuote receiveQuote(Integer amount) {
        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId(QUOTE_ID);
        quote.setInvoiceId(QUOTE_ID);
        quote.setDirection(Direction.RECEIVE);
        quote.setState(State.PAID);
        quote.setAmount(amount);
        quote.setUnit("sat");
        return quote;
    }

    private PhoenixWebhookPayload payload(Integer amountSat) {
        return new PhoenixWebhookPayload("payment_received", amountSat, PAYMENT_HASH, QUOTE_ID);
    }

    /** The case that was broken: a paid mint quote with no payment row of its own. */
    @Test
    @DisplayName("forwards to the mint when a RECEIVE quote has no payment record")
    void forwardsIncomingPaymentWithoutAPaymentRecord() throws Exception {
        when(quoteClient.getByInvoiceId(QUOTE_ID)).thenReturn(receiveQuote(AMOUNT_SAT));
        stubNoPaymentRecord();
        when(mintForwarder.isEnabled()).thenReturn(true);

        var result = handler().handle(payload(AMOUNT_SAT));

        ArgumentCaptor<PaymentNotification> sent = ArgumentCaptor.forClass(PaymentNotification.class);
        verify(mintForwarder).notifyPaymentReceived(sent.capture());
        assertThat(sent.getValue().getQuoteId()).isEqualTo(QUOTE_ID);
        assertThat(result.newState()).isEqualTo(State.CONFIRMED);
    }

    /** Paying an amount other than the one invoiced is still refused, and nothing is forwarded. */
    @Test
    @DisplayName("rejects an amount that does not match the quote")
    void rejectsAmountMismatch() {
        when(quoteClient.getByInvoiceId(QUOTE_ID)).thenReturn(receiveQuote(AMOUNT_SAT));
        stubNoPaymentRecord();

        assertThatThrownBy(() -> handler().handle(payload(AMOUNT_SAT + 1)))
                .isInstanceOf(WebhookProcessingException.class)
                .hasMessageContaining("Amount mismatch");

        verify(mintForwarder, never()).notifyPaymentReceived(any());
    }

    /** A settled payment must still be reported even if the mint cannot be reached. */
    @Test
    @DisplayName("does not fail the webhook when the mint is unreachable")
    void survivesAFailingForwarder() throws Exception {
        when(quoteClient.getByInvoiceId(QUOTE_ID)).thenReturn(receiveQuote(AMOUNT_SAT));
        stubNoPaymentRecord();
        when(mintForwarder.isEnabled()).thenReturn(true);
        Mockito.doThrow(new RuntimeException("mint down"))
                .when(mintForwarder).notifyPaymentReceived(any());

        var result = handler().handle(payload(AMOUNT_SAT));

        assertThat(result.newState()).isEqualTo(State.CONFIRMED);
    }

    /**
     * A delivered payment is stamped, so the sweep knows to leave it alone
     * (398ja/cashu-mint#462).
     */
    @Test
    @DisplayName("stamps the quote when the mint acknowledges the payment")
    void recordsASuccessfulForward() throws Exception {
        GatewayQuote quote = receiveQuote(AMOUNT_SAT);
        when(quoteClient.getByInvoiceId(QUOTE_ID)).thenReturn(quote);
        stubNoPaymentRecord();
        when(mintForwarder.isEnabled()).thenReturn(true);
        when(mintForwarder.notifyPaymentReceived(any())).thenReturn(true);

        handler().handle(payload(AMOUNT_SAT));

        ArgumentCaptor<GatewayQuote> saved = ArgumentCaptor.forClass(GatewayQuote.class);
        verify(quoteClient).updateQuote(saved.capture());
        assertThat(saved.getValue().getMintNotifiedAt())
                .as("a delivered payment must be stamped, or the sweep re-delivers it forever")
                .isNotNull();
    }

    /**
     * The defect itself. The forwarder exhausts its retries and returns false; that boolean used
     * to be discarded, leaving the quote PAID with no record anywhere that the mint was never
     * told. Seven payments reached staging in exactly this state.
     *
     * <p>{@code mintNotifiedAt} must stay null so the sweep and the gauge can both see it.
     */
    @Test
    @DisplayName("leaves the quote unstamped when the mint never acknowledges - issue #462")
    void doesNotRecordAForwardThatFailed() throws Exception {
        GatewayQuote quote = receiveQuote(AMOUNT_SAT);
        when(quoteClient.getByInvoiceId(QUOTE_ID)).thenReturn(quote);
        stubNoPaymentRecord();
        when(mintForwarder.isEnabled()).thenReturn(true);
        // Three retries exhausted inside the forwarder, then false.
        when(mintForwarder.notifyPaymentReceived(any())).thenReturn(false);

        var result = handler().handle(payload(AMOUNT_SAT));

        // phoenixd is still told the webhook succeeded: the payment HAS settled, and failing
        // here would have it retry a completed payment.
        assertThat(result.newState()).isEqualTo(State.CONFIRMED);
        assertThat(quote.getMintNotifiedAt())
                .as("an undelivered payment must stay visible to the sweep and the gauge")
                .isNull();
        verify(quoteClient, never()).updateQuote(any());
    }

    /** A forward that throws is as undelivered as one that returns false. */
    @Test
    @DisplayName("leaves the quote unstamped when the forward throws")
    void doesNotRecordAForwardThatThrew() throws Exception {
        GatewayQuote quote = receiveQuote(AMOUNT_SAT);
        when(quoteClient.getByInvoiceId(QUOTE_ID)).thenReturn(quote);
        stubNoPaymentRecord();
        when(mintForwarder.isEnabled()).thenReturn(true);
        Mockito.doThrow(new RuntimeException("connection reset"))
                .when(mintForwarder).notifyPaymentReceived(any());

        handler().handle(payload(AMOUNT_SAT));

        assertThat(quote.getMintNotifiedAt()).isNull();
        verify(quoteClient, never()).updateQuote(any());
    }

    /**
     * The stamp is bookkeeping; the mint already has the payment. Losing the webhook response
     * over a failed write would be the more expensive mistake, so the handler swallows it and
     * accepts that the sweep may re-deliver and be answered {@code duplicate}.
     */
    @Test
    @DisplayName("a failed stamp does not fail the webhook")
    void survivesAFailingStamp() throws Exception {
        when(quoteClient.getByInvoiceId(QUOTE_ID)).thenReturn(receiveQuote(AMOUNT_SAT));
        stubNoPaymentRecord();
        when(mintForwarder.isEnabled()).thenReturn(true);
        when(mintForwarder.notifyPaymentReceived(any())).thenReturn(true);
        Mockito.doThrow(new RuntimeException("db down")).when(quoteClient).updateQuote(any());

        var result = handler().handle(payload(AMOUNT_SAT));

        assertThat(result.newState()).isEqualTo(State.CONFIRMED);
    }
}
