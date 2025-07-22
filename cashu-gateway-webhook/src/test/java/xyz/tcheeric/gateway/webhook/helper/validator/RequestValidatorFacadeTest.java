package xyz.tcheeric.gateway.webhook.helper.validator;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.client.QuoteClient;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.GatewayQuote;
import xyz.tcheeric.gateway.model.entity.enums.Direction;
import xyz.tcheeric.gateway.model.entity.enums.State;

import static org.junit.jupiter.api.Assertions.*;

public class RequestValidatorFacadeTest {

    @Test
    public void validatePhoenixd() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getParameter("wid")).thenReturn("A1b2C3d4");
        Mockito.when(request.getParameter("type")).thenReturn("payment_received");
        Mockito.when(request.getParameter("amountSat")).thenReturn("10");
        Mockito.when(request.getParameter("paymentHash")).thenReturn("hash");
        Mockito.when(request.getParameter("externalId")).thenReturn("invoice");

        GatewayQuote quote = new GatewayQuote();
        quote.setQuoteId("quote");
        quote.setDirection(Direction.RECEIVE);
        quote.setInvoiceId("invoice");

        GatewayPayment payment = new GatewayPayment();
        payment.setQuoteId("quote");
        payment.setPaymentHash("hash");
        payment.setAmount(10);
        payment.setState(State.PAID);

        try (MockedConstruction<QuoteClient> qc = Mockito.mockConstruction(QuoteClient.class,
                (mock, context) -> Mockito.when(mock.getByInvoiceId("invoice")).thenReturn(quote));
             MockedConstruction<PaymentClient> pc = Mockito.mockConstruction(PaymentClient.class,
                (mock, context) -> Mockito.when(mock.getByQuoteId("quote")).thenReturn(payment))) {
            GatewayPayment result = RequestValidatorFacade.validate(request);
            assertSame(payment, result);
        }
    }

    @Test
    public void validateDummy() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getParameter("wid")).thenReturn("deadbeef");

        try (MockedConstruction<PaymentClient> pc = Mockito.mockConstruction(PaymentClient.class)) {
            GatewayPayment result = RequestValidatorFacade.validate(request);
            assertEquals(State.PAID, result.getState());
            assertEquals(10, result.getAmount());
        }
    }

    @Test
    public void validateUnknownId() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getParameter("wid")).thenReturn("unknown");

        assertThrows(IllegalArgumentException.class, () -> RequestValidatorFacade.validate(request));
    }
}
