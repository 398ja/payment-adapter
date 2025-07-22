package xyz.tcheeric.gateway.webhook;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import xyz.tcheeric.gateway.client.PaymentClient;
import xyz.tcheeric.gateway.model.entity.GatewayPayment;
import xyz.tcheeric.gateway.model.entity.enums.State;
import xyz.tcheeric.gateway.webhook.helper.validator.RequestValidatorFacade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class WebhookServletTest {

    @Test
    void testDoPostUpdatesPayment() {
        GatewayPayment payment = new GatewayPayment();
        payment.setState(State.PAID);

        try (MockedStatic<RequestValidatorFacade> validatorMock = Mockito.mockStatic(RequestValidatorFacade.class);
             MockedConstruction<PaymentClient> paymentClientMock = Mockito.mockConstruction(PaymentClient.class)) {
            validatorMock.when(() -> RequestValidatorFacade.validate(any())).thenReturn(payment);

            WebhookServlet servlet = new WebhookServlet();
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            servlet.doPost(request, response);

            assertEquals(State.CONFIRMED, payment.getState());
            assertEquals(HttpServletResponse.SC_CREATED, response.getStatus());
            PaymentClient mockClient = paymentClientMock.constructed().get(0);
            verify(mockClient).updatePayment(payment);
        }
    }

    @Test
    void testDoPostValidationFailure() {
        try (MockedStatic<RequestValidatorFacade> validatorMock = Mockito.mockStatic(RequestValidatorFacade.class);
             MockedConstruction<PaymentClient> paymentClientMock = Mockito.mockConstruction(PaymentClient.class)) {
            validatorMock.when(() -> RequestValidatorFacade.validate(any())).thenThrow(new RuntimeException("fail"));

            WebhookServlet servlet = new WebhookServlet();
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            servlet.doPost(request, response);

            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
            assertTrue(paymentClientMock.constructed().isEmpty());
        }
    }
}
