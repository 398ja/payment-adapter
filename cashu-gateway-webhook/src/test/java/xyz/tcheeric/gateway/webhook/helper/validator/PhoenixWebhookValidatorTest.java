package xyz.tcheeric.gateway.webhook.helper.validator;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.client.QuoteClient;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;
import xyz.tcheeric.gateway.model.entity.enums.Direction;
import xyz.tcheeric.gateway.model.entity.enums.State;
import xyz.tcheeric.gateway.webhook.helper.PhoenixdWebhookRequest;

import static org.junit.jupiter.api.Assertions.*;

public class PhoenixWebhookValidatorTest {

    @Test
    public void validateSuccess() {
        PhoenixdWebhookRequest req = new PhoenixdWebhookRequest();
        req.setType("payment_received");
        req.setAmountSat(10);
        req.setPaymentHash("hash");
        req.setExternalId("invoice");

        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId("quote");
        quote.setInvoiceId("invoice");
        quote.setDirection(Direction.RECEIVE);

        GatewayPayment payment = new GatewayPayment();
        payment.setQuoteId("quote");
        payment.setAmount(10);
        payment.setPaymentHash("hash");
        payment.setState(State.PAID);

        PhoenixWebhookValidator validator = new PhoenixWebhookValidator(req);

        try (MockedConstruction<QuoteClient> qc = Mockito.mockConstruction(QuoteClient.class,
                (mock, context) -> Mockito.when(mock.getByInvoiceId("invoice")).thenReturn(quote));
             MockedConstruction<PaymentClient> pc = Mockito.mockConstruction(PaymentClient.class,
                (mock, context) -> Mockito.when(mock.getByQuoteId("quote")).thenReturn(payment))) {
            GatewayPayment result = validator.validate();
            assertSame(payment, result);
        }
    }

    @Test
    public void validateInvalidHash() {
        PhoenixdWebhookRequest req = new PhoenixdWebhookRequest();
        req.setType("payment_received");
        req.setAmountSat(10);
        req.setPaymentHash("bad");
        req.setExternalId("invoice");

        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId("quote");
        quote.setInvoiceId("invoice");
        quote.setDirection(Direction.RECEIVE);

        GatewayPayment payment = new GatewayPayment();
        payment.setQuoteId("quote");
        payment.setAmount(10);
        payment.setPaymentHash("hash");
        payment.setState(State.PAID);

        PhoenixWebhookValidator validator = new PhoenixWebhookValidator(req);

        try (MockedConstruction<QuoteClient> qc = Mockito.mockConstruction(QuoteClient.class,
                (mock, context) -> Mockito.when(mock.getByInvoiceId("invoice")).thenReturn(quote));
             MockedConstruction<PaymentClient> pc = Mockito.mockConstruction(PaymentClient.class,
                (mock, context) -> Mockito.when(mock.getByQuoteId("quote")).thenReturn(payment))) {
            assertThrows(IllegalArgumentException.class, validator::validate);
        }
    }

    @Test
    public void validateInvalidAmount() {
        PhoenixdWebhookRequest req = new PhoenixdWebhookRequest();
        req.setType("payment_received");
        req.setAmountSat(5);
        req.setPaymentHash("hash");
        req.setExternalId("invoice");

        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId("quote");
        quote.setInvoiceId("invoice");
        quote.setDirection(Direction.RECEIVE);

        GatewayPayment payment = new GatewayPayment();
        payment.setQuoteId("quote");
        payment.setAmount(10);
        payment.setPaymentHash("hash");
        payment.setState(State.PAID);

        PhoenixWebhookValidator validator = new PhoenixWebhookValidator(req);

        try (MockedConstruction<QuoteClient> qc = Mockito.mockConstruction(QuoteClient.class,
                (mock, context) -> Mockito.when(mock.getByInvoiceId("invoice")).thenReturn(quote));
             MockedConstruction<PaymentClient> pc = Mockito.mockConstruction(PaymentClient.class,
                (mock, context) -> Mockito.when(mock.getByQuoteId("quote")).thenReturn(payment))) {
            assertThrows(IllegalArgumentException.class, validator::validate);
        }
    }

    @Test
    public void validateInvalidState() {
        PhoenixdWebhookRequest req = new PhoenixdWebhookRequest();
        req.setType("payment_received");
        req.setAmountSat(10);
        req.setPaymentHash("hash");
        req.setExternalId("invoice");

        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId("quote");
        quote.setInvoiceId("invoice");
        quote.setDirection(Direction.RECEIVE);

        GatewayPayment payment = new GatewayPayment();
        payment.setQuoteId("quote");
        payment.setAmount(10);
        payment.setPaymentHash("hash");
        payment.setState(State.PENDING);

        PhoenixWebhookValidator validator = new PhoenixWebhookValidator(req);

        try (MockedConstruction<QuoteClient> qc = Mockito.mockConstruction(QuoteClient.class,
                (mock, context) -> Mockito.when(mock.getByInvoiceId("invoice")).thenReturn(quote));
             MockedConstruction<PaymentClient> pc = Mockito.mockConstruction(PaymentClient.class,
                (mock, context) -> Mockito.when(mock.getByQuoteId("quote")).thenReturn(payment))) {
            assertThrows(IllegalArgumentException.class, validator::validate);
        }
    }
}
