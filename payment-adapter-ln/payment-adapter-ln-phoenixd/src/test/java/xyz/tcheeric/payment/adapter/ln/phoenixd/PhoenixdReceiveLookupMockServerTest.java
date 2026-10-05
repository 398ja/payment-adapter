package xyz.tcheeric.payment.adapter.ln.phoenixd;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.Direction;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import xyz.tcheeric.payment.adapter.ln.phoenixd.service.AdapterWebhookRelay;
import xyz.tcheeric.payment.adapter.ln.phoenixd.service.PhoenixdServiceImpl;
import xyz.tcheeric.phoenixd.mock.MockLnServer;
import xyz.tcheeric.phoenixd.model.param.CreateInvoiceParam;
import xyz.tcheeric.phoenixd.model.response.CreateInvoiceResponse;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The RECEIVE quote lookup end to end over HTTP, against phoenixd-java's own mock server: create
 * an invoice, pay it, then ask the gateway whether the quote is paid. The gateway decodes the
 * invoice for its payment hash and looks the payment up under {@code /payments/incoming/{hash}}.
 *
 * <p>Only the adapter's own store (QuoteClient/PaymentClient) and the webhook replay are stubbed.
 * Everything that talks to phoenixd is real.
 */
class PhoenixdReceiveLookupMockServerTest {

    private MockLnServer phoenixd;
    private String baseUrl;
    private String previousBaseUrl;

    @BeforeEach
    void start() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        phoenixd = new MockLnServer(port);
        phoenixd.start();
        baseUrl = "http://127.0.0.1:" + port;
        previousBaseUrl = System.getProperty("phoenixd.base_url");
        System.setProperty("phoenixd.base_url", baseUrl);
    }

    @AfterEach
    void stop() {
        phoenixd.stop();
        if (previousBaseUrl == null) {
            System.clearProperty("phoenixd.base_url");
        } else {
            System.setProperty("phoenixd.base_url", previousBaseUrl);
        }
    }

    // A RECEIVE quote whose invoice phoenixd has been paid for, but whose webhook the adapter
    // never recorded, must read as paid. On phoenixd-java 0.3.0 the decoded payment hash was always
    // null, so this threw PaymentStatusUnavailableException (phoenixd_lookup_failed, mint 500).
    @Test
    void aPaidInvoiceIsFoundThroughDecodeAndIncomingPaymentLookup() throws Exception {
        PhoenixdServiceImpl service = new PhoenixdServiceImpl();
        CreateInvoiceResponse invoice = createInvoice(service, "q-e2e-paid", 30);
        pay(invoice.getPaymentHash());
        GatewayQuote quote = receiveQuote("q-e2e-paid", invoice.getSerialized());
        AdapterWebhookRelay relay = mock(AdapterWebhookRelay.class);
        when(relay.relayPaymentReceived(any(), anyString(), any(), anyString())).thenReturn(true);

        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (m, context) -> when(m.getByEntityId(anyString())).thenReturn(quote));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (m, context) -> when(m.getByQuoteId(anyString())).thenThrow(notFound()))
        ) {
            Assertions.assertTrue(new PhoenixdGateway(service, relay).checkPaymentStatus("q-e2e-paid"));

            verify(relay).relayPaymentReceived(any(), eq("q-e2e-paid"), eq(30), eq(invoice.getPaymentHash()));
        }
    }

    // An invoice phoenixd holds no payment for is a definite "not paid" (the mint answers 200
    // UNPAID), not an unavailable lookup.
    @Test
    void anInvoiceWithNoPaymentIsUnpaidNotUnavailable() {
        PhoenixdServiceImpl service = new PhoenixdServiceImpl();
        GatewayQuote quote = receiveQuote("q-e2e-unknown",
                "lnbc300n1pjqqqqqpp5qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsdqqcqzzs");

        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (m, context) -> when(m.getByEntityId(anyString())).thenReturn(quote));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (m, context) -> when(m.getByQuoteId(anyString())).thenThrow(notFound()))
        ) {
            Assertions.assertFalse(new PhoenixdGateway(service, mock(AdapterWebhookRelay.class))
                    .checkPaymentStatus("q-e2e-unknown"));
        }
    }

    private static CreateInvoiceResponse createInvoice(PhoenixdServiceImpl service, String externalId, int amountSat) {
        CreateInvoiceParam param = new CreateInvoiceParam();
        param.setAmountSat(amountSat);
        param.setDescription("e2e");
        param.setExternalId(externalId);
        CreateInvoiceResponse invoice = service.createInvoice(param);
        Assertions.assertNotNull(invoice.getPaymentHash());
        Assertions.assertNotNull(invoice.getSerialized());
        return invoice;
    }

    private void pay(String paymentHash) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/mockpay?paymentHash=" + paymentHash)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, response.statusCode(), response.body());
    }

    private static GatewayQuote receiveQuote(String quoteId, String bolt11) {
        GatewayQuote quote = new GatewayQuote();
        quote.setId(7L);
        quote.setQuoteId(quoteId);
        quote.setInvoiceId(quoteId);
        quote.setAmount(30);
        quote.setRequest(bolt11);
        quote.setState(State.PENDING);
        quote.setDirection(Direction.RECEIVE);
        return quote;
    }

    private static HttpClientErrorException notFound() {
        return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, new byte[0], null);
    }
}
