package xyz.tcheeric.gateway.phoenixd;

import xyz.tcheeric.phoenixd.model.response.CreateInvoiceResponse;
import xyz.tcheeric.phoenixd.model.response.GetLightningAddressResponse;
import xyz.tcheeric.phoenixd.model.response.PayInvoiceResponse;
import xyz.tcheeric.phoenixd.request.impl.rest.CreateBolt11InvoiceRequest;
import xyz.tcheeric.phoenixd.request.impl.rest.GetLightningAddressRequest;
import xyz.tcheeric.phoenixd.request.impl.rest.PayBolt11InvoiceRequest;
import xyz.tcheeric.phoenixd.request.impl.rest.PayLightningAddressRequest;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.client.QuoteClient;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;
import xyz.tcheeric.gateway.model.entity.enums.State;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.gateway.common.Gateway;

@NoArgsConstructor
@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
public class PhoenixdGatewayTest {

    private PhoenixdGateway gateway;

    @BeforeEach
    public void init() {
        gateway = new PhoenixdGateway();
    }

    @AfterEach
    public void shutdown() {
        System.clearProperty("wid");
    }

    @Test
    public void testCreateMintQuote() throws Exception {
        CreateInvoiceResponse createResp = new CreateInvoiceResponse();
        createResp.setSerialized("lninvoice");
        createResp.setAmountSat(10);
        GetLightningAddressResponse addressResp = new GetLightningAddressResponse();
        addressResp.setLightningAddress("bob@ln");

        System.setProperty("wid", "testCreateMintQuote");
        GatewayQuote[] savedQuote = new GatewayQuote[1];
        try (
            MockedConstruction<CreateBolt11InvoiceRequest> invoice = mockConstruction(CreateBolt11InvoiceRequest.class,
                    (mock, context) -> when(mock.getResponse()).thenReturn(createResp));
            MockedConstruction<GetLightningAddressRequest> address = mockConstruction(GetLightningAddressRequest.class,
                    (mock, context) -> when(mock.getResponse()).thenReturn(addressResp));
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

    @Test
    public void testPayInvoice() throws Exception {
        PayInvoiceResponse payResp = new PayInvoiceResponse();
        payResp.setPaymentId("pid");
        payResp.setPaymentPreimage("pre");
        payResp.setPaymentHash("hash");
        payResp.setRecipientAmountSat(10);
        payResp.setRoutingFeeSat(1);

        GatewayQuote[] savedQuote = new GatewayQuote[1];
        GatewayPayment[] savedPayment = new GatewayPayment[1];
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
            MockedConstruction<PayBolt11InvoiceRequest> bolt11 = mockConstruction(PayBolt11InvoiceRequest.class,
                    (mock, context) -> when(mock.getResponse()).thenReturn(payResp));
            MockedConstruction<PayLightningAddressRequest> lnAddr = mockConstruction(PayLightningAddressRequest.class,
                    (mock, context) -> when(mock.getResponse()).thenReturn(payResp))
        ) {

            String quoteId = gateway.createMeltQuote(10, "bob@ln", "testPayInvoice");
            String paymentId = gateway.pay(quoteId);

            GatewayPayment payment = new PaymentClient().getByEntityId(paymentId);

            Assertions.assertNotNull(payment);
            Assertions.assertEquals(paymentId, payment.getPaymentId());
            Assertions.assertEquals(State.PAID, payment.getState());
        }
    }

    @Test
    public void testPayInvoiceFailure() {
        PayInvoiceResponse payResp = new PayInvoiceResponse();
        payResp.setReason("FAILURE");

        GatewayQuote[] savedQuote = new GatewayQuote[1];
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
            MockedConstruction<PayBolt11InvoiceRequest> bolt11 = mockConstruction(PayBolt11InvoiceRequest.class,
                    (mock, context) -> when(mock.getResponse()).thenReturn(payResp));
            MockedConstruction<PayLightningAddressRequest> lnAddr = mockConstruction(PayLightningAddressRequest.class,
                    (mock, context) -> when(mock.getResponse()).thenReturn(payResp))
        ) {

            String quoteId = gateway.createMeltQuote(10, "bob@ln", "errorPay");

            Assertions.assertThrows(IllegalStateException.class, () -> gateway.pay(quoteId));
        }
    }

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
}
