package xyz.tcheeric.payment.adapter.paypal.connect;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import xyz.tcheeric.payment.adapter.paypal.connect.exception.PaypalConnectException;
import xyz.tcheeric.payment.adapter.stripe.connect.model.StripeConnectErrorResponse;

/** The same {@code {code, message}} error body as Stripe's Connect endpoints. */
@RestControllerAdvice(assignableTypes = PaypalConnectController.class)
public class PaypalConnectExceptionHandler {

    @ExceptionHandler(PaypalConnectException.class)
    public ResponseEntity<StripeConnectErrorResponse> handle(PaypalConnectException e) {
        return ResponseEntity.status(e.getStatus()).body(new StripeConnectErrorResponse(e.getCode().name(), e.getMessage()));
    }
}
