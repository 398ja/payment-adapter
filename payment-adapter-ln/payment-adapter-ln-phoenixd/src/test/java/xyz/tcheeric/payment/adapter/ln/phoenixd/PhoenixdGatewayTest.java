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
    // verifies that webhook URL appends the gateway name
    @Test
    public void testWebhookUrlAppendsGatewayName() throws Exception {
        java.lang.reflect.Method method = PhoenixdGateway.class.getDeclaredMethod("getWebhookUrl");
        method.setAccessible(true);
        java.net.URL url = (java.net.URL) method.invoke(gateway);
        Assertions.assertEquals("http://localhost:9090/webhook/phoenixd", url.toString());
    }

}
