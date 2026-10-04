package xyz.tcheeric.payment.adapter.ln.phoenixd;

import xyz.tcheeric.phoenixd.model.response.CreateInvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.GetLightningAddressResponse;
import xyz.tcheeric.payment.adapter.ln.phoenixd.service.PhoenixdService;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;
import xyz.tcheeric.payment.adapter.core.client.PaymentClient;
import xyz.tcheeric.payment.adapter.core.client.QuoteClient;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayPayment;
import xyz.tcheeric.payment.adapter.core.model.entity.GatewayQuote;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.payment.adapter.core.common.Gateway;
import xyz.tcheeric.phoenixd.model.response.PayBolt11InvoiceInvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.PayLightningAddressInvoiceResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import xyz.tcheeric.payment.adapter.core.common.InvoiceNotPaidException;
import xyz.tcheeric.payment.adapter.core.common.PaymentStatusUnavailableException;
import xyz.tcheeric.payment.adapter.core.common.QuoteRef;
import xyz.tcheeric.payment.adapter.core.model.entity.enums.Direction;
import xyz.tcheeric.payment.adapter.ln.phoenixd.service.AdapterWebhookRelay;
import xyz.tcheeric.payment.adapter.ln.phoenixd.service.IncomingPaymentResponse;
import xyz.tcheeric.phoenixd.model.response.DecodeInvoiceResponse;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Properties;

@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
public class PhoenixdGatewayTest {

    private PhoenixdGateway gateway;
    @Mock
    private PhoenixdService service;

    @BeforeEach
    public void init() throws Exception {
        gateway = new PhoenixdGateway(service);

        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("app.properties")) {
            props.load(in);
        }
        setField("currency", props.getProperty("phoenixd.currency"));
        setField("expiry", Integer.parseInt(props.getProperty("phoenixd.expiry")));
        setField("lnAddressFlag", props.getProperty("phoenixd.lnaddress"));
        setField("feePercent", Double.parseDouble(props.getProperty("phoenixd.fee.percent")));
        setField("fixedFee", Integer.parseInt(props.getProperty("phoenixd.fee.fixed")));
        setField("webhookBaseUrl", props.getProperty("webhook.base_url"));
    }

    private void setField(String name, Object value) throws Exception {
        Field field = PhoenixdGateway.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(gateway, value);
    }


    // verifies creating a mint quote persists a pending quote
    @Test
    public void testCreateMintQuote() throws Exception {
        CreateInvoiceResponse createResp = new CreateInvoiceResponse();
        createResp.setSerialized("lninvoice");
        createResp.setAmountSat(10);
        GetLightningAddressResponse addressResp = new GetLightningAddressResponse();
        addressResp.setLightningAddress("bob@ln");

        GatewayQuote[] savedQuote = new GatewayQuote[1];
        when(service.createInvoice(any())).thenReturn(createResp);
        when(service.getLightningAddress()).thenReturn(addressResp);
        try (
            MockedConstruction<QuoteClient> mocked = mockConstruction(QuoteClient.class,
                    (mock, context) -> {
                        when(mock.create(any(GatewayQuote.class))).thenAnswer(inv -> {
                            GatewayQuote q = inv.getArgument(0);
                            savedQuote[0] = q;
                            return q;
                        });
                        when(mock.getByEntityId(anyString())).thenAnswer(inv -> savedQuote[0]);
                    })
        ) {

            String quoteId = gateway.createMintQuote(10, "testCreateMintQuote");

            GatewayQuote quote = new QuoteClient().getByEntityId(quoteId);

            Assertions.assertNotNull(quote);
            Assertions.assertEquals(quoteId, quote.getQuoteId());
            Assertions.assertEquals(State.PENDING, quote.getState());
        }
    }

    // A quote created under a caller-chosen id must be created under exactly that id, end to end.
    // The mint records the voucher quote under this id BEFORE raising the invoice (cashu-mint#469),
    // so if the gateway substituted its own id the mint's record would name an invoice that does not
    // exist. Three places must agree: the returned id, the persisted quote, and the externalId phoenixd
    // echoes back on the payment webhook, which is how an incoming payment is matched to its quote.
    @Test
    public void createMintQuoteUsesTheCallersIdEverywhere() throws Exception {
        String callersId = "7f3c2b1a-0000-4000-8000-000000000469";
        CreateInvoiceResponse createResp = new CreateInvoiceResponse();
        createResp.setSerialized("lninvoice");
        createResp.setAmountSat(10);
        when(service.createInvoice(any())).thenReturn(createResp);
        when(service.getLightningAddress()).thenReturn(new GetLightningAddressResponse());

        GatewayQuote[] savedQuote = new GatewayQuote[1];
        try (
            MockedConstruction<QuoteClient> mocked = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.create(any(GatewayQuote.class))).thenAnswer(inv -> {
                        savedQuote[0] = inv.getArgument(0);
                        return savedQuote[0];
                    }))
        ) {
            String returned = gateway.createMintQuote(callersId, 10, "voucher");

            Assertions.assertEquals(callersId, returned, "the returned id must be the caller's");
            Assertions.assertEquals(callersId, savedQuote[0].getQuoteId(), "the persisted quote id");
            Assertions.assertEquals(callersId, savedQuote[0].getInvoiceId(), "the persisted invoice id");
            verify(service).createInvoice(argThat(param -> callersId.equals(param.getExternalId())));
        }
    }

    // The original two-argument form still generates its own id, and a fresh one each time. It now
    // delegates to the caller-id form, so this pins that the delegation did not collapse ids together.
    @Test
    public void createMintQuoteWithoutAnIdGeneratesAFreshOne() throws Exception {
        CreateInvoiceResponse createResp = new CreateInvoiceResponse();
        createResp.setSerialized("lninvoice");
        createResp.setAmountSat(10);
        when(service.createInvoice(any())).thenReturn(createResp);
        when(service.getLightningAddress()).thenReturn(new GetLightningAddressResponse());

        try (MockedConstruction<QuoteClient> mocked = mockConstruction(QuoteClient.class,
                (mock, context) -> when(mock.create(any(GatewayQuote.class))).thenAnswer(inv -> inv.getArgument(0)))) {
            String first = gateway.createMintQuote(10, "a");
            String second = gateway.createMintQuote(10, "b");

            Assertions.assertNotNull(first);
            Assertions.assertNotEquals(first, second, "each generated quote must get its own id");
        }
    }

    // A blank id is refused before any invoice is raised. Raising one under a blank externalId would
    // produce an invoice whose payment could never be matched to a quote, which is the stranded-payment
    // shape this method exists to prevent.
    @Test
    public void createMintQuoteRefusesABlankIdBeforeRaisingAnInvoice() throws Exception {
        Assertions.assertThrows(IllegalArgumentException.class, () -> gateway.createMintQuote(" ", 10, "x"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> gateway.createMintQuote((String) null, 10, "x"));

        verify(service, never()).createInvoice(any());
    }

    // verifies paying a BOLT11 invoice results in a paid payment record
    @Test
    public void testPayBoltInvoice() throws Exception {
        PayBolt11InvoiceInvoiceResponse payResp = new PayBolt11InvoiceInvoiceResponse();
        payResp.setPaymentId("pid");
        payResp.setPaymentPreimage("pre");
        payResp.setPaymentHash("hash");
        payResp.setRecipientAmountSat(10);
        payResp.setRoutingFeeSat(1);

        GatewayQuote[] savedQuote = new GatewayQuote[1];
        GatewayPayment[] savedPayment = new GatewayPayment[1];
        when(service.payBolt11Invoice(any())).thenReturn(payResp);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> {
                        when(mock.create(any(GatewayQuote.class))).thenAnswer(inv -> {
                            GatewayQuote q = inv.getArgument(0);
                            savedQuote[0] = q;
                            return q;
                        });
                        when(mock.getByEntityId(anyString())).thenAnswer(inv -> savedQuote[0]);
                    });
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> {
                        when(mock.create(any(GatewayPayment.class))).thenAnswer(inv -> {
                            GatewayPayment p = inv.getArgument(0);
                            savedPayment[0] = p;
                            return p;
                        });
                        when(mock.getByEntityId(anyString())).thenAnswer(inv -> savedPayment[0]);
                    });

        ) {

            String quoteId = gateway.createMeltQuote(10, "lnbc1testinvoice", "testPayBoltInvoice");
            String paymentId = gateway.pay(quoteId);

            GatewayPayment payment = new PaymentClient().getByEntityId(paymentId);

            Assertions.assertNotNull(payment);
            Assertions.assertEquals(paymentId, payment.getPaymentId());
            Assertions.assertEquals(State.PAID, payment.getState());
        }
    }

    // ensures the created payment carries the exact quoteId used to initiate payment (no stale/mismatched quoteId)
    @Test
    public void testQuoteIdConsistencyOnPay() throws Exception {
        PayBolt11InvoiceInvoiceResponse payResp = new PayBolt11InvoiceInvoiceResponse();
        payResp.setPaymentId("pid2");
        payResp.setPaymentPreimage("pre2");
        payResp.setPaymentHash("hash2");
        payResp.setRecipientAmountSat(5);
        payResp.setRoutingFeeSat(1);

        GatewayQuote[] savedQuote = new GatewayQuote[1];
        GatewayPayment[] savedPayment = new GatewayPayment[1];
        when(service.payBolt11Invoice(any())).thenReturn(payResp);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                (mock, context) -> {
                    when(mock.create(any(GatewayQuote.class))).thenAnswer(inv -> {
                        GatewayQuote q = inv.getArgument(0);
                        savedQuote[0] = q;
                        return q;
                    });
                    when(mock.getByEntityId(anyString())).thenAnswer(inv -> savedQuote[0]);
                });
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                (mock, context) -> {
                    when(mock.create(any(GatewayPayment.class))).thenAnswer(inv -> {
                        GatewayPayment p = inv.getArgument(0);
                        savedPayment[0] = p;
                        return p;
                    });
                })
        ) {
            String quoteId = gateway.createMeltQuote(5, "lnbc1consistency", "consistency");
            gateway.pay(quoteId);

            Assertions.assertNotNull(savedPayment[0]);
            Assertions.assertEquals(quoteId, savedPayment[0].getQuoteId());
        }
    }

    // verifies paying with an unknown/stale quoteId is rejected with a clear error
    @Test
    public void testPayWithUnknownQuoteIdThrows() {
        PayBolt11InvoiceInvoiceResponse payResp = new PayBolt11InvoiceInvoiceResponse();
        payResp.setPaymentId("pid");
        payResp.setRecipientAmountSat(1);
        payResp.setRoutingFeeSat(0);
        //                                                                                                                                                          when(service.payBolt11Invoice(any())).thenReturn(payResp);

        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                (mock, context) -> {
                    // Simulate repository returning null for unknown quoteId
                    when(mock.getByEntityId(anyString())).thenReturn(null);
                });
            MockedConstruction<PaymentClient> ignored = mockConstruction(PaymentClient.class)
        ) {
            Assertions.assertThrows(IllegalStateException.class, () -> gateway.pay("stale-or-unknown-quote"));
        }
    }

    // verifies paying a lightning address invoice results in a paid payment record
    @Test
    public void testPayLnInvoice() throws Exception {
        PayLightningAddressInvoiceResponse payResp = new PayLightningAddressInvoiceResponse();
        payResp.setPaymentId("pid");
        payResp.setPaymentPreimage("pre");
        payResp.setPaymentHash("hash");
        payResp.setRecipientAmountSat(10);
        payResp.setRoutingFeeSat(1);

        GatewayQuote[] savedQuote = new GatewayQuote[1];
        GatewayPayment[] savedPayment = new GatewayPayment[1];
        when(service.payLightningAddress(any())).thenReturn(payResp);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> {
                        when(mock.create(any(GatewayQuote.class))).thenAnswer(inv -> {
                            GatewayQuote q = inv.getArgument(0);
                            savedQuote[0] = q;
                            return q;
                        });
                        when(mock.getByEntityId(anyString())).thenAnswer(inv -> savedQuote[0]);
                    });
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> {
                        when(mock.create(any(GatewayPayment.class))).thenAnswer(inv -> {
                            GatewayPayment p = inv.getArgument(0);
                            savedPayment[0] = p;
                            return p;
                        });
                        when(mock.getByEntityId(anyString())).thenAnswer(inv -> savedPayment[0]);
                    });

        ) {

            String quoteId = gateway.createMeltQuote("eyJsbkFkZHJlc3MiOiAiYm9iQGxuIiwgImFtb3VudCI6IDEwLCAiZGVzY3JpcHRpb24iOiAidGVzdCJ9");
            String paymentId = gateway.pay(quoteId);

            GatewayPayment payment = new PaymentClient().getByEntityId(paymentId);

            Assertions.assertNotNull(payment);
            Assertions.assertEquals(paymentId, payment.getPaymentId());
            Assertions.assertEquals(State.PAID, payment.getState());
        }
    }

    // verifies payment throws when lightning address payment fails
    @Test
    public void testPayLnInvoiceFailure() {
        PayLightningAddressInvoiceResponse payResp = new PayLightningAddressInvoiceResponse();
        payResp.setReason("FAILURE");

        GatewayQuote[] savedQuote = new GatewayQuote[1];
        when(service.payLightningAddress(any())).thenReturn(payResp);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> {
                        when(mock.create(any(GatewayQuote.class))).thenAnswer(inv -> {
                            GatewayQuote q = inv.getArgument(0);
                            savedQuote[0] = q;
                            return q;
                        });
                        when(mock.getByEntityId(anyString())).thenAnswer(inv -> savedQuote[0]);
                    });
            MockedConstruction<PaymentClient> ignored = mockConstruction(PaymentClient.class);

        ) {

            String quoteId = gateway.createMeltQuote("eyJsbkFkZHJlc3MiOiAiYm9iQGxuIiwgImFtb3VudCI6IDEwLCAiZGVzY3JpcHRpb24iOiAidGVzdCJ9");

        Assertions.assertThrows(IllegalStateException.class, () -> gateway.pay(quoteId));
        }
    }

    // verifies payment throws when BOLT11 invoice payment fails
    @Test
    public void testPayBoltInvoiceFailure() {
        PayBolt11InvoiceInvoiceResponse payResp = new PayBolt11InvoiceInvoiceResponse();
        payResp.setReason("FAILURE");

        GatewayQuote[] savedQuote = new GatewayQuote[1];
        when(service.payBolt11Invoice(any())).thenReturn(payResp);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> {
                        when(mock.create(any(GatewayQuote.class))).thenAnswer(inv -> {
                            GatewayQuote q = inv.getArgument(0);
                            savedQuote[0] = q;
                            return q;
                        });
                        when(mock.getByEntityId(anyString())).thenAnswer(inv -> savedQuote[0]);
                    });
            MockedConstruction<PaymentClient> ignored = mockConstruction(PaymentClient.class);

        ) {

            String quoteId = gateway.createMeltQuote(10, "lnbc1failinvoice", "errorPayBolt");

            Assertions.assertThrows(IllegalStateException.class, () -> gateway.pay(quoteId));
        }
    }

    // verifies payment throws when service returns null response
    @Test
    public void testNullServiceResponse() {
        GatewayQuote[] savedQuote = new GatewayQuote[1];
        when(service.payBolt11Invoice(any())).thenReturn(null);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> {
                        when(mock.create(any(GatewayQuote.class))).thenAnswer(inv -> {
                            GatewayQuote q = inv.getArgument(0);
                            savedQuote[0] = q;
                            return q;
                        });
                        when(mock.getByEntityId(anyString())).thenAnswer(inv -> savedQuote[0]);
                    });
            MockedConstruction<PaymentClient> ignored = mockConstruction(PaymentClient.class);

        ) {

            String quoteId = gateway.createMeltQuote(10, "lnbc1nullinvoice", "nullServiceResp");

            Assertions.assertThrows(IllegalStateException.class, () -> gateway.pay(quoteId));
        }
    }

    // verifies supported payment methods
    @Test
    public void testSupports()  {
        // Arrange & Act
        Gateway gateway = new PhoenixdGateway();

        // Assert
        Assertions.assertTrue(gateway.supports(PaymentMethod.BOLT11));
        Assertions.assertTrue(gateway.supports(PaymentMethod.BOLT12));
        Assertions.assertTrue(gateway.supports(PaymentMethod.ON_CHAIN));
        Assertions.assertFalse(gateway.supports(PaymentMethod.CASH));
        Assertions.assertFalse(gateway.supports(PaymentMethod.PAYMENT_API));
        Assertions.assertFalse(gateway.supports(PaymentMethod.CREDIT_CARD));
        Assertions.assertFalse(gateway.supports(PaymentMethod.MOBILE_MONEY));
        Assertions.assertFalse(gateway.supports(PaymentMethod.MOCK));
    }
    // A quote lookup that fails for a transient reason (here a timeout) must not be reported as
    // "not paid", even when no Payment record exists. The quote may well be PAID; we just could not
    // read it. Reporting it unpaid makes the mint answer 20001, and a caller past the quote's expiry
    // forgets a paid voucher (#253). It must surface as an error the mint answers with a 5xx.
    @Test
    public void transientQuoteLookupFailureIsAnErrorNotUnpaid() {
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString()))
                            .thenThrow(new ResourceAccessException("Read timed out")));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> when(mock.getByQuoteId(anyString()))
                            .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                                    HttpHeaders.EMPTY, new byte[0], null)))
        ) {
            PaymentStatusUnavailableException thrown = Assertions.assertThrows(
                    PaymentStatusUnavailableException.class,
                    () -> gateway.checkPaymentStatus("q-timeout"));
            Assertions.assertEquals("q-timeout", thrown.getQuoteId());
        }
    }

    // A quote lookup answered by a server error (the adapter's REST store returned 503) is just as
    // unknown as a timeout, so it is also an error and never "not paid".
    @Test
    public void quoteLookupServerErrorIsAnErrorNotUnpaid() {
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString()))
                            .thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                                    "Service Unavailable", HttpHeaders.EMPTY, new byte[0], null)));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class)
        ) {
            Assertions.assertThrows(PaymentStatusUnavailableException.class,
                    () -> gateway.checkPaymentStatus("q-503"));
        }
    }

    // A genuinely unpaid quote still reads as unpaid: the quote is found and PENDING, and there is
    // no Payment record. That is a definite answer, so it is a plain false, which the mint reports
    // as a 200 with state UNPAID (NUT-04) rather than an error a caller cannot tell from a fault.
    @Test
    public void pendingQuoteWithNoPaymentIsDefinitelyUnpaid() {
        GatewayQuote pending = new GatewayQuote();
        pending.setQuoteId("q-pending");
        pending.setState(State.PENDING);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString())).thenReturn(pending));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> when(mock.getByQuoteId(anyString()))
                            .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                                    HttpHeaders.EMPTY, new byte[0], null)))
        ) {
            Assertions.assertFalse(gateway.checkPaymentStatus("q-pending"));
        }
    }

    // A quote the store definitely does not have (404) is still allowed to fall back to the Payment
    // record, which older MELT quotes rely on. With no Payment either, the answer is a definite unpaid.
    @Test
    public void quoteNotFoundFallsBackToPaymentAndReadsUnpaidWhenThatIsMissingToo() {
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString()))
                            .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                                    HttpHeaders.EMPTY, new byte[0], null)));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> when(mock.getByQuoteId(anyString()))
                            .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                                    HttpHeaders.EMPTY, new byte[0], null)))
        ) {
            Assertions.assertThrows(InvoiceNotPaidException.class,
                    () -> gateway.checkPaymentStatus("q-gone"));
        }
    }

    // A PAID quote is reported paid without consulting the Payment record at all.
    @Test
    public void paidQuoteIsPaid() {
        GatewayQuote paid = new GatewayQuote();
        paid.setQuoteId("q-paid");
        paid.setState(State.PAID);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString())).thenReturn(paid));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class)
        ) {
            Assertions.assertTrue(gateway.checkPaymentStatus("q-paid"));
            Assertions.assertTrue(payments.constructed().isEmpty());
        }
    }

    // A store lookup for the Payment record that fails with a server error (the adapter's REST
    // store answered 500) is as unknown as a failed quote lookup. The quote itself was found and is
    // not PAID, but the Payment record might say PAID, so the answer must be "unknown", never "unpaid".
    @Test
    public void paymentLookupServerErrorIsAnErrorNotUnpaid() {
        GatewayQuote pending = receiveQuote("q-pay-500");
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString())).thenReturn(pending));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> when(mock.getByQuoteId(anyString()))
                            .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                                    "Internal Server Error", HttpHeaders.EMPTY, new byte[0], null)))
        ) {
            PaymentStatusUnavailableException thrown = Assertions.assertThrows(
                    PaymentStatusUnavailableException.class,
                    () -> gateway.checkPaymentStatus("q-pay-500"));
            Assertions.assertEquals("q-pay-500", thrown.getQuoteId());
            verify(service, never()).getIncomingPayment(anyString());
        }
    }

    // The lost-webhook case. The invoice was paid at phoenixd, but its callback never reached the
    // adapter, so the stored quote still reads PENDING and there is no Payment record. Answering
    // "unpaid" here makes the mint report UNPAID and the gateway forget a paid voucher. The gateway
    // must ask phoenixd, and on "paid" record the payment (mark the quote PAID), replay phoenixd's
    // callback to the adapter's webhook so the mint is told as it would have been, and answer paid.
    @Test
    public void receiveQuotePaidAtPhoenixdIsReconciledAndReportedPaid() {
        GatewayQuote pending = receiveQuote("q-lost-webhook");
        stubDecode("hash-lost");
        IncomingPaymentResponse paid = incoming("hash-lost", true, 30L);
        when(service.getIncomingPayment("hash-lost")).thenReturn(paid);
        AdapterWebhookRelay relay = mock(AdapterWebhookRelay.class);
        when(relay.relayPaymentReceived(any(), anyString(), any(), anyString())).thenReturn(true);
        PhoenixdGateway reconciling = gatewayWithRelay(relay);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString())).thenReturn(pending));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> when(mock.getByQuoteId(anyString())).thenThrow(notFound()))
        ) {
            Assertions.assertTrue(reconciling.checkPaymentStatus("q-lost-webhook"));

            verify(quotes.constructed().get(quotes.constructed().size() - 1)).markPaid(pending.getId());
            verify(relay).relayPaymentReceived(
                    argThat(url -> url.toString().equals("http://localhost:9090/webhook/phoenixd")),
                    eq("q-lost-webhook"), eq(30), eq("hash-lost"));
        }
    }

    // phoenixd itself says the invoice is not paid. That is a definite answer, so the quote reads
    // unpaid (the mint answers 200 with state UNPAID), and nothing is recorded or forwarded.
    @Test
    public void receiveQuoteUnpaidAtPhoenixdStaysUnpaid() {
        GatewayQuote pending = receiveQuote("q-unpaid");
        stubDecode("hash-unpaid");
        when(service.getIncomingPayment("hash-unpaid")).thenReturn(incoming("hash-unpaid", false, 0L));
        AdapterWebhookRelay relay = mock(AdapterWebhookRelay.class);
        PhoenixdGateway reconciling = gatewayWithRelay(relay);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString())).thenReturn(pending));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> when(mock.getByQuoteId(anyString())).thenThrow(notFound()))
        ) {
            Assertions.assertFalse(reconciling.checkPaymentStatus("q-unpaid"));

            quotes.constructed().forEach(q -> verify(q, never()).markPaid(any()));
            verifyNoInteractions(relay);
        }
    }

    // phoenixd holds no payment at all for the invoice's hash (it answers 404, which the service
    // returns as null). That is also a definite "not paid".
    @Test
    public void receiveQuoteWithNoPaymentAtPhoenixdIsUnpaid() {
        GatewayQuote pending = receiveQuote("q-none");
        stubDecode("hash-none");
        when(service.getIncomingPayment("hash-none")).thenReturn(null);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString())).thenReturn(pending));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> when(mock.getByQuoteId(anyString())).thenThrow(notFound()))
        ) {
            Assertions.assertFalse(gateway.checkPaymentStatus("q-none"));
        }
    }

    // phoenixd cannot be reached, so whether the invoice was paid is unknown. That must be the
    // "unknown" answer (the mint answers 5xx and callers retry), never "unpaid", and the quote
    // id must not appear in the exception message, which callers log (cashu-mint#531).
    @Test
    public void receiveQuoteWithPhoenixdUnreachableIsUnknown() {
        GatewayQuote pending = receiveQuote("q-phoenixd-down");
        stubDecode("hash-down");
        when(service.getIncomingPayment("hash-down"))
                .thenThrow(new IllegalStateException("java.net.ConnectException: Connection refused"));
        AdapterWebhookRelay relay = mock(AdapterWebhookRelay.class);
        PhoenixdGateway reconciling = gatewayWithRelay(relay);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString())).thenReturn(pending));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> when(mock.getByQuoteId(anyString())).thenThrow(notFound()))
        ) {
            PaymentStatusUnavailableException thrown = Assertions.assertThrows(
                    PaymentStatusUnavailableException.class,
                    () -> reconciling.checkPaymentStatus("q-phoenixd-down"));
            Assertions.assertEquals("q-phoenixd-down", thrown.getQuoteId());
            Assertions.assertFalse(thrown.getMessage().contains("q-phoenixd-down"));
            quotes.constructed().forEach(q -> verify(q, never()).markPaid(any()));
            verifyNoInteractions(relay);
        }
    }

    // When phoenixd says paid but recording the payment fails, the answer is still "paid": the
    // money is at phoenixd. Nothing is forwarded yet, since the mint would then hear of a payment
    // the adapter has no record of; the next status check asks phoenixd again.
    @Test
    public void reconcileRecordFailureStillAnswersPaidAndDoesNotForward() {
        GatewayQuote pending = receiveQuote("q-record-fails");
        stubDecode("hash-rf");
        when(service.getIncomingPayment("hash-rf")).thenReturn(incoming("hash-rf", true, 30L));
        AdapterWebhookRelay relay = mock(AdapterWebhookRelay.class);
        PhoenixdGateway reconciling = gatewayWithRelay(relay);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> {
                        when(mock.getByEntityId(anyString())).thenReturn(pending);
                        when(mock.markPaid(any())).thenThrow(new ResourceAccessException("Read timed out"));
                    });
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> when(mock.getByQuoteId(anyString())).thenThrow(notFound()))
        ) {
            Assertions.assertTrue(reconciling.checkPaymentStatus("q-record-fails"));
            verifyNoInteractions(relay);
        }
    }

    // A SEND (melt) quote with no Payment record has not been paid by this gateway, which is a
    // definite answer on its own: phoenixd is not asked about incoming payments for it.
    @Test
    public void sendQuoteWithNoPaymentIsUnpaidWithoutAskingPhoenixd() {
        GatewayQuote send = receiveQuote("q-send");
        send.setDirection(Direction.SEND);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString())).thenReturn(send));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> when(mock.getByQuoteId(anyString())).thenThrow(notFound()))
        ) {
            Assertions.assertFalse(gateway.checkPaymentStatus("q-send"));
            verify(service, never()).getIncomingPayment(anyString());
        }
    }

    // A quote id is a bearer claim on the customer's payment (cashu-mint#531), so the WARN and ERROR
    // lines the gateway writes on its unknown and reconcile paths must carry the quote's ref, never
    // the id itself. Drives both paths and reads every line the gateway logged.
    @Test
    public void unknownAndReconcileLogsCarryTheQuoteRefNotTheId() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PhoenixdGateway.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        String secretId = "5b7a958a-77a3-4ad0-a67b-e5aa00000531";
        GatewayQuote pending = receiveQuote(secretId);
        stubDecode("hash-log");
        when(service.getIncomingPayment("hash-log")).thenReturn(incoming("hash-log", true, 30L));
        AdapterWebhookRelay relay = mock(AdapterWebhookRelay.class);
        PhoenixdGateway reconciling = gatewayWithRelay(relay);
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString())).thenReturn(pending));
            MockedConstruction<PaymentClient> payments = mockConstruction(PaymentClient.class,
                    (mock, context) -> when(mock.getByQuoteId(anyString()))
                            .thenThrow(notFound())
                            .thenThrow(new ResourceAccessException("Read timed out")))
        ) {
            // Reconcile path: paid at phoenixd, relay refused, so it logs WARN and an [alert] ERROR.
            Assertions.assertTrue(reconciling.checkPaymentStatus(secretId));
        }
        try (
            MockedConstruction<QuoteClient> quotes = mockConstruction(QuoteClient.class,
                    (mock, context) -> when(mock.getByEntityId(anyString()))
                            .thenThrow(new ResourceAccessException("Read timed out")))
        ) {
            // Unknown path: the store lookup fails.
            Assertions.assertThrows(PaymentStatusUnavailableException.class,
                    () -> reconciling.checkPaymentStatus(secretId));
        } finally {
            logger.detachAppender(appender);
        }

        java.util.List<String> lines = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        Assertions.assertTrue(lines.stream().anyMatch(l -> l.contains("payment_reconciled")), lines.toString());
        Assertions.assertTrue(lines.stream().anyMatch(l -> l.contains("state=UNKNOWN")), lines.toString());
        Assertions.assertTrue(lines.stream().anyMatch(l -> l.contains(QuoteRef.of(secretId))), lines.toString());
        Assertions.assertTrue(lines.stream().noneMatch(l -> l.contains(secretId)), lines.toString());
    }

    private static GatewayQuote receiveQuote(String quoteId) {
        GatewayQuote quote = new GatewayQuote();
        quote.setId(42L);
        quote.setQuoteId(quoteId);
        quote.setInvoiceId(quoteId);
        quote.setAmount(30);
        quote.setRequest("lnbc300n1ptest");
        quote.setState(State.PENDING);
        quote.setDirection(Direction.RECEIVE);
        return quote;
    }

    private void stubDecode(String paymentHash) {
        DecodeInvoiceResponse decoded = new DecodeInvoiceResponse();
        decoded.setPaymentHash(paymentHash);
        when(service.decodeInvoice(any())).thenReturn(decoded);
    }

    private static IncomingPaymentResponse incoming(String paymentHash, boolean paid, long receivedSat) {
        IncomingPaymentResponse response = new IncomingPaymentResponse();
        response.setPaymentHash(paymentHash);
        response.setPaid(paid);
        response.setReceivedSat(receivedSat);
        return response;
    }

    private static HttpClientErrorException notFound() {
        return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, new byte[0], null);
    }

    private PhoenixdGateway gatewayWithRelay(AdapterWebhookRelay relay) {
        PhoenixdGateway reconciling = new PhoenixdGateway(service, relay);
        for (String name : new String[]{"currency", "expiry", "lnAddressFlag", "feePercent", "fixedFee", "webhookBaseUrl"}) {
            try {
                Field field = PhoenixdGateway.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(reconciling, field.get(gateway));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return reconciling;
    }

    // verifies that webhook URL appends the gateway name
    @Test
    public void testWebhookUrlAppendsGatewayName() throws Exception {
        java.lang.reflect.Method method = PhoenixdGateway.class.getDeclaredMethod("getWebhookUrl");
        method.setAccessible(true);
        java.net.URL url = (java.net.URL) method.invoke(gateway);
        Assertions.assertEquals("http://localhost:9090/webhook/phoenixd", url.toString());
    }

}
