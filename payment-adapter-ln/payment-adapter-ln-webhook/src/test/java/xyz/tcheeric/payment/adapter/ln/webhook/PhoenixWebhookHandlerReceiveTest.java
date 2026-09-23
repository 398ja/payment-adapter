package xyz.tcheeric.payment.adapter.ln.webhook;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
        // A quote read back from the gateway API always carries its database id, and the stamp
        // is addressed by it. The fixture used to omit it, so `stampMintNotified(anyLong(), ..)`
        // matched nothing and the assertions passed against a call that never happened.
        quote.setId(4242L);
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
        ListAppender<ILoggingEvent> alerts = captureAlerts();
        GatewayQuote quote = receiveQuote(AMOUNT_SAT);
        when(quoteClient.getByInvoiceId(QUOTE_ID)).thenReturn(quote);
        stubNoPaymentRecord();
        when(mintForwarder.isEnabled()).thenReturn(true);
        when(mintForwarder.notifyPaymentReceived(any())).thenReturn(true);
        // The server echoes the stamped row, and it must echo the state the fixture already
        // has. Stubbing PENDING against a PAID fixture invents a settled payment going
        // BACKWARDS -- exactly the regression the alert exists to catch -- so the happy-path
        // test would have fired a critical alert while reporting success.
        stubStampReturns(stampedCopy(quote, quote.getState()));

        handler().handle(payload(AMOUNT_SAT));

        ArgumentCaptor<Instant> stampedAt = ArgumentCaptor.forClass(Instant.class);
        verify(quoteClient).stampMintNotified(eq(quote.getId()), stampedAt.capture());
        assertThat(stampedAt.getValue())
                .as("a delivered payment must be stamped, or the sweep re-delivers it forever")
                .isNotNull();

        // The point of #245: the stamp must not be able to write `state` at all. A full-object
        // PUT here reverted a concurrently-PAID quote to PENDING and cost three real sales.
        verify(quoteClient, never()).updateQuote(any());
        assertThat(alertsIn(alerts))
                .as("a healthy forward must raise nothing; an alert on the normal path is how "
                        + "operators learn to ignore alerts")
                .isEmpty();
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
        verify(quoteClient, never()).stampMintNotified(anyLong(), any());
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
        verify(quoteClient, never()).stampMintNotified(anyLong(), any());
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
        Mockito.doThrow(new RuntimeException("db down"))
                .when(quoteClient).stampMintNotified(anyLong(), any());

        var result = handler().handle(payload(AMOUNT_SAT));

        assertThat(result.newState()).isEqualTo(State.CONFIRMED);
    }

    /**
     * What the server sends back from a partial update: the whole row, as it now stands.
     *
     * <p>{@code state} is a parameter because the interesting cases differ only in that field --
     * a concurrent payment settling mid-request is the NORMAL case, not an anomaly.
     */
    private static GatewayQuote stampedCopy(GatewayQuote source, State state) {
        GatewayQuote stamped = new GatewayQuote();
        stamped.setId(source.getId());
        stamped.setQuoteId(source.getQuoteId());
        stamped.setInvoiceId(source.getInvoiceId());
        stamped.setAmount(source.getAmount());
        stamped.setDirection(source.getDirection());
        stamped.setState(state);
        stamped.setMintNotifiedAt(Instant.parse("2026-09-23T16:32:37Z"));
        return stamped;
    }

    private void stubStampReturns(GatewayQuote response) {
        when(quoteClient.stampMintNotified(anyLong(), any())).thenReturn(response);
    }

    /**
     * Captures what the handler actually LOGGED.
     *
     * <p>Asserting on state alone is not enough for the alerts: a check that cries wolf on the
     * healthy path leaves every field correct and still costs an operator their attention, which
     * is how `payment_missing` ended up firing on every successful mint poll. The only way a
     * test can hold that line is to read the log.
     */
    private ListAppender<ILoggingEvent> captureAlerts() {
        Logger logger = (Logger) LoggerFactory.getLogger(PhoenixWebhookHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static List<String> alertsIn(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("[alert]"))
                .toList();
    }

    /**
     * The stamp is accepted and does not land.
     *
     * <p>This is #245's shape exactly -- a write that answers 2xx and changes nothing -- so the
     * handler must SAY so rather than trust the status. Before the response was checked, this
     * scenario was indistinguishable from success.
     */
    @Test
    @DisplayName("says so when the stamp is accepted but does not land")
    void reportsALostStamp() throws Exception {
        ListAppender<ILoggingEvent> alerts = captureAlerts();
        GatewayQuote quote = receiveQuote(AMOUNT_SAT);
        when(quoteClient.getByInvoiceId(QUOTE_ID)).thenReturn(quote);
        stubNoPaymentRecord();
        when(mintForwarder.isEnabled()).thenReturn(true);
        when(mintForwarder.notifyPaymentReceived(any())).thenReturn(true);

        GatewayQuote notStamped = stampedCopy(quote, State.PENDING);
        notStamped.setMintNotifiedAt(null);          // accepted, not applied
        stubStampReturns(notStamped);

        handler().handle(payload(AMOUNT_SAT));

        assertThat(quote.getMintNotifiedAt())
                .as("a stamp that did not land must not be recorded locally as though it had, "
                        + "or the sweep skips a payment the mint may never have heard about")
                .isNull();
        assertThat(alertsIn(alerts))
                .as("a lost write must be SAID, not swallowed -- being silent here is the "
                        + "original defect")
                .anyMatch(m -> m.contains("mint_notified_stamp_lost"));
    }

    /**
     * A payment settling DURING the request is the ordinary case, not a fault.
     *
     * <p>The handler's own copy is stale by construction -- that staleness is what #245 was --
     * so a check comparing local against server state would fire here on a perfectly healthy
     * sale. This pins that it stays quiet, because an alert that cries wolf on the normal path
     * is the failure mode this estate keeps rediscovering.
     */
    @Test
    @DisplayName("stays quiet when the payment settles concurrently, which is the normal case")
    void toleratesAConcurrentSettlement() throws Exception {
        ListAppender<ILoggingEvent> alerts = captureAlerts();
        GatewayQuote quote = receiveQuote(AMOUNT_SAT);
        // The handler's copy is stale BY CONSTRUCTION: it was read before phoenixd settled the
        // invoice. Forcing it to PENDING here is what makes this the #245 timeline rather than a
        // repeat of the happy path -- the fixture is PAID by default, and leaving it that way
        // would have made this test assert nothing the previous one did not.
        quote.setState(State.PENDING);
        when(quoteClient.getByInvoiceId(QUOTE_ID)).thenReturn(quote);
        stubNoPaymentRecord();
        when(mintForwarder.isEnabled()).thenReturn(true);
        when(mintForwarder.notifyPaymentReceived(any())).thenReturn(true);
        // phoenixd marked it PAID between the read and the stamp. The server's echo is the truth.
        stubStampReturns(stampedCopy(quote, State.PAID));

        handler().handle(payload(AMOUNT_SAT));

        assertThat(quote.getMintNotifiedAt())
                .as("a concurrent settlement is success, and the stamp must still be recorded")
                .isNotNull();
        // The assertion this test exists for. A naive `stamped.state != local.state` check passes
        // every other assertion here and still fires, because the local copy is stale by
        // construction. Only reading the log catches that.
        assertThat(alertsIn(alerts))
                .as("a payment settling mid-request is the NORMAL case and must raise no alert")
                .isEmpty();
    }
}
